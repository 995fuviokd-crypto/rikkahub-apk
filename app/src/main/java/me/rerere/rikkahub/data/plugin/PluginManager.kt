package me.rerere.rikkahub.data.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 插件包管理器：插件以 zip 包形式安装，包内根目录必须有 plugin.json。
 * 安装目录为 filesDir/plugins/<pluginId>/，与技能目录（filesDir/skills）隔离。
 */
class PluginManager(
    private val context: Context,
) {
    fun getPluginsDir(): File {
        val dir = context.filesDir.resolve(PLUGIN_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getPluginDir(pluginId: String): File = getPluginsDir().resolve(pluginId)

    /** 列出已安装插件，目录缺失 plugin.json 或解析失败的标记为损坏 */
    fun listPlugins(): List<InstalledPlugin> {
        return getPluginsDir().listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val infoFile = dir.resolve(METADATA_FILE)
                val info = if (infoFile.exists()) {
                    runCatching {
                        PluginJson.fromJson(infoFile.readText())
                    }.onFailure { Log.w(TAG, "parse plugin.json failed: ${dir.name}", it) }
                        .getOrNull()
                } else {
                    null
                }
                if (info == null) {
                    InstalledPlugin(
                        id = dir.name,
                        info = null,
                        status = PluginStatus.BROKEN,
                    )
                } else {
                    InstalledPlugin(
                        id = info.id,
                        info = info,
                        status = PluginStatus.INSTALLED,
                    )
                }
            }
            ?: emptyList()
    }

    fun getInstalled(pluginId: String): InstalledPlugin? {
        val dir = getPluginDir(pluginId)
        if (!dir.isDirectory) return null
        val info = runCatching { PluginJson.fromJson(dir.resolve(METADATA_FILE).readText()) }.getOrNull()
        return if (info != null) {
            InstalledPlugin(id = pluginId, info = info, status = PluginStatus.INSTALLED)
        } else {
            InstalledPlugin(id = pluginId, info = null, status = PluginStatus.BROKEN)
        }
    }

    fun loadInfo(pluginId: String): PluginInfo? {
        val dir = getPluginDir(pluginId)
        val infoFile = dir.resolve(METADATA_FILE)
        if (!infoFile.exists()) return null
        return runCatching { PluginJson.fromJson(infoFile.readText()) }.getOrNull()
    }

    /** 已启用插件的系统提示文本列表（顺序按插件 id 稳定） */
    fun enabledSystemPrompts(enabledPlugins: Set<String>): List<String> {
        if (enabledPlugins.isEmpty()) return emptyList()
        return enabledPlugins
            .sorted()
            .mapNotNull { loadInfo(it) }
            .filter { it.systemPrompt.isNotBlank() }
            .map { it.systemPrompt }
    }

    /** 已启用插件的快捷操作列表 */
    fun enabledActions(enabledPlugins: Set<String>): List<PluginAction> {
        if (enabledPlugins.isEmpty()) return emptyList()
        return enabledPlugins
            .sorted()
            .mapNotNull { loadInfo(it) }
            .flatMap { it.actions }
            .distinctBy { it.label }
    }

    /** 已启用插件的扩展能力入口（按 scope 过滤） */
    fun enabledExtensionActions(
        enabledPlugins: Set<String>,
        scope: String,
    ): List<PluginExtensionAction> {
        if (enabledPlugins.isEmpty()) return emptyList()
        return enabledPlugins
            .sorted()
            .mapNotNull { loadInfo(it) }
            .flatMap { info ->
                when (scope) {
                    "settings" -> info.extensionPoints.settingsActions
                    "home" -> info.extensionPoints.homeActions
                    "sidebar" -> info.extensionPoints.sidebarActions
                    else -> emptyList()
                }
            }
            .distinctBy { it.id }
    }

    /**
     * 读取插件包内 web/ 目录下的页面资源文本（用于 webview 扩展入口）。
     * relativePath 相对插件目录下的 web/，防路径穿越。
     */
    fun loadWebResource(pluginId: String, relativePath: String): String? {
        val webRoot = File(getPluginDir(pluginId), "web")
        val file = File(webRoot, relativePath)
        val canonicalRoot = runCatching { webRoot.canonicalPath }.getOrNull() ?: return null
        val canonicalFile = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (!canonicalFile.startsWith(canonicalRoot)) return null
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** 内置插件包制作技能 id（随 App 预置，可在已安装列表卸载） */
    suspend fun ensureBuiltinSkill(): Boolean {
        val skillId = "builtin-plugin-maker"
        if (getPluginDir(skillId).exists()) return false
        val bytes = runCatching { context.assets.open("plugin-maker-skill.zip").readBytes() }.getOrNull() ?: return false
        return runCatching { installZip(bytes) }.getOrNull()?.isSuccess == true
    }

    /** 从插件 zip 字节中提取 plugin.json（用于上传前校验与生成市场条目） */
    fun parseArchive(bytes: ByteArray): Result<PluginInfo> = Companion.extractPluginInfo(bytes)

    /** 卸载插件目录 */
    suspend fun uninstall(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = getPluginDir(pluginId)
        dir.isDirectory && dir.deleteRecursively()
    }

    /** 安装插件 zip：解压到临时目录，校验 plugin.json，原子替换到 plugins/<id> */
    suspend fun installZip(bytes: ByteArray): Result<PluginInfo> = withContext(Dispatchers.IO) {
        val pluginsDir = getPluginsDir()
        val staging = createTempDirectory(pluginsDir)
        try {
            Companion.unzipTo(bytes, staging)
            val infoFile = staging.resolve(METADATA_FILE)
            if (!infoFile.exists()) {
                return@withContext Result.failure(IllegalArgumentException("插件包缺少 plugin.json"))
            }
            val info = runCatching { PluginJson.fromJson(infoFile.readText()) }
                .getOrElse { return@withContext Result.failure(IllegalArgumentException("plugin.json 解析失败")) }
            if (info.id.isBlank() || info.name.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("plugin.json 缺少 id 或 name"))
            }

            val targetDir = pluginsDir.resolve(info.id)
            // 旧版本备份，异常时回滚
            val backup = createTempDirectory(pluginsDir)
            if (targetDir.exists()) {
                if (!targetDir.renameTo(backup)) {
                    return@withContext Result.failure(IllegalStateException("无法备份旧插件版本"))
                }
            }
            if (!staging.renameTo(targetDir)) {
                if (backup.listFiles()?.isNotEmpty() == true || !targetDir.exists()) {
                    backup.renameTo(targetDir)
                }
                return@withContext Result.failure(IllegalStateException("安装插件失败"))
            }
            backup.deleteRecursively()
            Result.success(info)
        } catch (e: Throwable) {
            Log.w(TAG, "installZip failed", e)
            Result.failure(e)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun createTempDirectory(parent: File): File {
        val dir = File(parent, ".staging_${System.nanoTime()}")
        if (!dir.mkdirs()) error("Failed to create temp dir")
        return dir
    }

    companion object {
        private const val TAG = "PluginManager"
        const val PLUGIN_DIR_NAME = "plugins"
        const val METADATA_FILE = "plugin.json"

        /** 从插件 zip 字节中提取 plugin.json。纯 JVM 可测，不依赖 Context。
         *  优先取包根目录的 plugin.json；找不到时退而取任意子目录内的（兼容打包目录嵌套）。 */
        fun extractPluginInfo(bytes: ByteArray): Result<PluginInfo> {
            return runCatching {
                var rootInfo: ByteArray? = null
                var fallbackInfo: ByteArray? = null
                ZipInputStream(bytes.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.trimStart('/')
                        if (name.split('/').lastOrNull() == METADATA_FILE) {
                            val content = zip.readBytes()
                            if (name == METADATA_FILE) {
                                rootInfo = content
                                break
                            } else if (fallbackInfo == null) {
                                fallbackInfo = content
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                val jsonBytes = rootInfo ?: fallbackInfo ?: error("插件包缺少 $METADATA_FILE")
                PluginJson.fromJson(jsonBytes.toString(Charsets.UTF_8))
            }
        }

        /** 解压 zip 到目标目录，过滤路径穿越。纯 JVM 可测。 */
        fun unzipTo(bytes: ByteArray, targetDir: File) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.trimStart('/')
                    val safePath = name.split('/').fold(StringBuilder()) { acc, part ->
                        if (acc.isNotEmpty() && acc.last() != '/') acc.append('/')
                        when (part) {
                            "", ".", ".." -> {}
                            else -> acc.append(part)
                        }
                        acc
                    }.toString()
                    if (safePath.isBlank()) {
                        entry = zip.nextEntry
                        continue
                    }
                    val target = File(targetDir, safePath)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }
}

enum class PluginStatus {
    /** 已安装（目录存在且 plugin.json 合法），是否生效取决于是否启用 */
    INSTALLED,

    /** 已安装且已启用（systemPrompt 注入 + 快捷操作生效） */
    ENABLED,

    /** 损坏：目录存在但 plugin.json 缺失/解析失败 */
    BROKEN,
}

data class InstalledPlugin(
    val id: String,
    val info: PluginInfo?,
    val status: PluginStatus,
)

object PluginJson {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun fromJson(text: String): PluginInfo = json.decodeFromString(PluginInfo.serializer(), text)

    fun toJson(info: PluginInfo): String = json.encodeToString(PluginInfo.serializer(), info)
}
