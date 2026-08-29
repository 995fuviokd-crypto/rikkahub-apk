package me.rerere.androidvm

import android.content.Context
import android.util.Log

/**
 * 宿主接入点：在 [me.rerere.rikkahub.RikkaHubApp] 的 attachBaseContext / onCreate 中调用，
 * 用于初始化 Android 虚拟化引擎（黑盒 BlackBox）。
 *
 * 采用反射调用，保证本模块在**未引入 BlackBoxCore 时不依赖、不编译报错**：
 * 当 Bcore 以库形式进入最终 APK（真机环境），反射即激活；否则所有调用静默 no-op。
 *
 * 接入的必要条件（真机完成）：
 * 1. 将 gitee.com/dingzhuangjian/BlackBox 的 Bcore 模块作为依赖加入 RikkaHub
 *    （见本仓库 third_party/BlackBox 子模块；启用需在 settings.gradle include 并解决 NDK / compileSdk / free_reflection）；
 * 2. 在宿主提供一个 [android.content.Context] 包名下的 ClientConfiguration 具体子类，
 *    并将其全限定名填入 [CLIENT_CONFIGURATION_CLASS]（BlackBoxCore.doAttachBaseContext 需要它）；
 * 3. 合并 BlackBox 的 stub 组件清单（见 androidvm 的 blackbox_stub_manifest.xml 片段）；
 * 4. 修改 RikkaHubApp 继承/挂接初始化（本文件已预留 attachBaseContext / onCreate 调用）。
 *
 * 已对照 BlackBoxCore 源码核实的方法（top.niunaijun.blackbox.BlackBoxCore）：
 * - static BlackBoxCore get()
 * - void doAttachBaseContext(Context, ClientConfiguration)
 * - void doCreate()
 * - InstallResult installPackageAsUser(String|File|Uri, int userId)
 * - boolean launchApk(String packageName, int userId)
 * - List<PackageInfo> getInstalledPackages(int flags, int userId)
 * - void uninstallPackageAsUser(String packageName, int userId)
 */
object BlackBoxHost {
    private const val TAG = "BlackBoxHost"
    private const val CORE_CLASS = "top.niunaijun.blackbox.BlackBoxCore"
    private const val CONFIG_CLASS = "top.niunaijun.blackbox.client.ClientConfiguration"

    /**
     * 宿主提供的 ClientConfiguration 具体子类全限定名。
     * 默认指向 app 模块的 RikkaBoxConfig（仅在 blackbox.enable=true 时编译进 APK）。
     * 当 Bcore 未接入（coreClass 为空）时初始化自动跳过，无需改此值。
     */
    private const val CLIENT_CONFIGURATION_CLASS = "me.rerere.rikkahub.vm.RikkaBoxConfig"

    private fun coreClass(): Class<*>? = runCatching { Class.forName(CORE_CLASS) }.getOrNull()

    fun isAvailable(): Boolean = coreClass() != null

    private fun newConfigInstance(base: Context): Any? {
        if (CLIENT_CONFIGURATION_CLASS.isBlank()) return null
        return runCatching {
            Class.forName(CLIENT_CONFIGURATION_CLASS)
                .getDeclaredConstructor(Context::class.java)
                .newInstance(base)
        }.onFailure { Log.w(TAG, "创建 ClientConfiguration 失败: $it") }.getOrNull()
    }

    fun attachBaseContext(base: Context) {
        val cls = coreClass() ?: return
        val config = newConfigInstance(base) ?: run {
            Log.w(TAG, "未配置 CLIENT_CONFIGURATION_CLASS，跳过 BlackBox 初始化")
            return
        }
        runCatching {
            val core = cls.getMethod("get").invoke(null)
            cls.getMethod("doAttachBaseContext", Context::class.java, Class.forName(CONFIG_CLASS))
                .invoke(core, base, config)
        }.onFailure { Log.e(TAG, "BlackBox doAttachBaseContext 失败", it) }
    }

    fun onCreate() {
        val cls = coreClass() ?: return
        runCatching {
            val core = cls.getMethod("get").invoke(null)
            cls.getMethod("doCreate").invoke(core)
        }.onFailure { Log.e(TAG, "BlackBox doCreate 失败", it) }
    }

    /**
     * 引擎配置开关状态共享。
     *
     * 写入与 app 模块 RikkaBoxConfig 相同的 SharedPreferences（MODE_MULTI_PROCESS），
     * 让宿主进程与虚拟 app 进程都读到一致状态：Bcore 的 ClientConfiguration
     * （isVirtualRootEnabled / isHideRoot / isHideXposed）由 RikkaBoxConfig 从该偏好返回，
     * 从而驱动 VirtualRootHelper 与 IO 重定向的对应行为。
     *
     * 该方法不依赖 Bcore 是否存在（纯偏好写入），未接入引擎时静默 no-op。
     */
    private fun writeEnginePref(context: Context, key: String, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)
                .edit().putBoolean(key, enabled).apply()
        }.onFailure { Log.w(TAG, "写入偏好 $key 失败", it) }
    }

    /** 虚拟 root：虚拟空间内 su 探测返回「存在」。 */
    fun setVirtualRoot(context: Context, enabled: Boolean) =
        writeEnginePref(context, KEY_VIRTUAL_ROOT, enabled)

    /** 隐藏 root（反检测）：IO 重定向把 su 路径指向 -fake。 */
    fun setHideRoot(context: Context, enabled: Boolean) =
        writeEnginePref(context, KEY_HIDE_ROOT, enabled)

    /** 隐藏 Xposed（反检测）：包列表把 XP 安装器包列入 black。 */
    fun setHideXposed(context: Context, enabled: Boolean) =
        writeEnginePref(context, KEY_HIDE_XPOSED, enabled)

    // 与 app 模块 RikkaBoxConfig 的 companion 常量保持一致（双写双读）。
    private const val PREFS_NAME = "rikkahub_vm"
    private const val KEY_VIRTUAL_ROOT = "virtual_root"
    private const val KEY_HIDE_ROOT = "hide_root"
    private const val KEY_HIDE_XPOSED = "hide_xposed"
}
