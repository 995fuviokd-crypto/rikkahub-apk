package me.rerere.rikkahub

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import okhttp3.OkHttpClient
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.RestoreStaging
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.group.GroupScheduler
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.FloatingBubbleService
import me.rerere.rikkahub.service.KeepAliveService
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.data.ai.agent.AcpRuntime
import me.rerere.rikkahub.data.ai.agent.ScriptMcpBridge
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import me.rerere.androidvm.BlackBoxHost
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val KEEP_ALIVE_NOTIFICATION_CHANNEL_ID = "keep_alive"
const val FLOATING_BUBBLE_NOTIFICATION_CHANNEL_ID = "floating_bubble"

class RikkaHubApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 仿光速虚拟机 Android 虚拟化引擎初始化（未引入黑盒 BlackBox 模块时为 no-op）
        BlackBoxHost.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        // 应用备份恢复暂存区：若存在待应用数据库文件，必须在 Koin/Room 构建
        // （任何数据库连接打开）之前覆盖到正式路径，否则运行中的数据库连接
        // 因文件被替换而失效，进程内协程访问会立即闪退
        runCatching { RestoreStaging.applyIfPending(this) }.onFailure {
            Log.e(TAG, "apply pending database restore failed", it)
        }

        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidLogger()
                androidContext(this@RikkaHubApp)
                workManagerFactory()
                modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
            }
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Init QuickJS native library：loadLibrary 含 APK 解压 so 的磁盘 I/O，
        // 放后台线程避免冷启动主线程卡顿；JS 工具都在 IO 线程执行，初始化完成后即可用
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching { QuickJSLoader.init() }.onFailure {
                Log.e(TAG, "QuickJS init failed", it)
            }
        }

        // 预加载冷启动期必用资源（后台执行，首帧后即就绪）：
        // - jieba 词典解压：14MB assets 首次解压较重，提前做避免数据库 onOpen 时竞争首帧
        // - MCP 网络栈：Ktor HttpClient 构建提前完成，首次 MCP 连接无需再初始化
        get<AppScope>().launch(Dispatchers.Default) {
            runCatching { SimpleDictManager.extractDict(this@RikkaHubApp) }.onFailure {
                Log.e(TAG, "preload jieba dict failed", it)
            }
            runCatching { get<McpManager>().preload() }.onFailure {
                Log.e(TAG, "preload McpManager failed", it)
            }
        }

        // 预热聊天服务依赖链：ChatService 单例首次解析会级联构建
        // ChatService → ConversationRepository → AppDatabase(Room)、ProviderManager、
        // TemplateTransformer、LocalTools 等；在后台提前完成，避免用户进入聊天页时
        // 主线程 Koin 解析触发 Room 构建与各类初始化造成的卡顿（Koin 单例解析线程安全）
        get<AppScope>().launch(Dispatchers.Default) {
            runCatching {
                get<ChatService>()
                get<SettingsStore>()
                // 预热 Coil ImageLoader 依赖的 HTTP 客户端，避免首帧 setContent 时
                // 同步构建 OkHttpClient（连接池初始化）造成的首帧延迟
                get<OkHttpClient>()
            }.onFailure {
                Log.e(TAG, "preload chat service chain failed", it)
            }
        }

        // 启动任务中心定时调度器（守护协程，每分钟检查 cron 触发的群组任务）
        get<AppScope>().launch(Dispatchers.Default) {
            runCatching { get<GroupScheduler>().start() }.onFailure {
                Log.e(TAG, "start GroupScheduler failed", it)
            }
        }

        // 启动 DSH/脚本插件运行时协调者：把已启用插件同步进 CordisKernel 并热插拔
        get<AppScope>().launch(Dispatchers.Default) {
            runCatching { get<me.rerere.rikkahub.data.plugin.CordisRuntimeHost>().start() }.onFailure {
                Log.e(TAG, "start CordisRuntimeHost failed", it)
            }
        }

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // 后台保活: 进应用即在前台消息栏常驻显示"正在运行中"
        startKeepAliveIfEnabled()

        // 悬浮球: 若已开启则恢复系统级悬浮球
        startFloatingBubbleIfEnabled()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)

        // 仿光速虚拟机 Android 虚拟化引擎初始化（未引入黑盒 BlackBox 模块时为 no-op）
        BlackBoxHost.onCreate()
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch(Dispatchers.Default) {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch(Dispatchers.Default) {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun startKeepAliveIfEnabled() {
        get<AppScope>().launch(Dispatchers.Default) {
            runCatching {
                delay(300)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (!settings.keepAliveEnabled) return@launch
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@RikkaHubApp,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "startKeepAliveIfEnabled: notification permission not granted, skipping")
                    return@launch
                }
                val intent = Intent(this@RikkaHubApp, KeepAliveService::class.java).apply {
                    action = KeepAliveService.ACTION_START
                }
                startForegroundService(intent)
            }.onFailure {
                Log.e(TAG, "startKeepAliveIfEnabled failed", it)
            }
        }
    }

    private fun startFloatingBubbleIfEnabled() {
        get<AppScope>().launch(Dispatchers.Default) {
            runCatching {
                delay(300)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (!settings.floatingBubbleEnabled) return@launch
                if (!Settings.canDrawOverlays(this@RikkaHubApp)) {
                    Log.w(TAG, "startFloatingBubbleIfEnabled: overlay permission not granted, skipping")
                    return@launch
                }
                val intent = Intent(this@RikkaHubApp, FloatingBubbleService::class.java).apply {
                    action = FloatingBubbleService.ACTION_START
                }
                startForegroundService(intent)
            }.onFailure {
                Log.e(TAG, "startFloatingBubbleIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        val keepAliveChannel = NotificationChannelCompat
            .Builder(KEEP_ALIVE_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_keep_alive))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(keepAliveChannel)

        val floatingBubbleChannel = NotificationChannelCompat
            .Builder(FLOATING_BUBBLE_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_floating_bubble))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(floatingBubbleChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        runCatching { get<AppScope>().cancel() }
        runCatching { get<AcpRuntime>().closeAll() }
        runCatching { get<ScriptMcpBridge>().stop() }
        stopService(Intent(this, WebServerService::class.java))
        stopService(Intent(this, KeepAliveService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
