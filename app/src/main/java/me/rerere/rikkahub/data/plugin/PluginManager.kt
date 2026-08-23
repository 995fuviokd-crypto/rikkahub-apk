package me.rerere.rikkahub.data.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.serverUrl
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

    /** 已启用插件中是否包含 脚本/ToolPkg 插件（存在脚本目录且含 JS 文件） */
    fun hasScriptPlugins(enabledPlugins: Set<String>): Boolean {
        if (enabledPlugins.isEmpty()) return false
        return enabledPlugins.any { id ->
            val dir = me.rerere.rikkahub.data.script.ScriptRuntime.scriptDir(getPluginDir(id))
            dir.isDirectory && dir.listFiles()?.any { it.extension == "js" } == true
        }
    }

    /** 已安装的 skill 类型插件列表（供技能页合并展示）。type=skill 的插件既可注入 systemPrompt，也可作为技能查看/启用 */
    fun listPluginSkills(enabledPlugins: Set<String>): List<PluginSkillInfo> {
        return getPluginsDir().listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val infoFile = dir.resolve(METADATA_FILE)
                if (!infoFile.exists()) return@mapNotNull null
                val info = runCatching { PluginJson.fromJson(infoFile.readText()) }.getOrNull() ?: return@mapNotNull null
                if (info.type != PluginCategories.TYPE_SKILL) return@mapNotNull null
                PluginSkillInfo(
                    pluginId = info.id,
                    name = info.name.ifBlank { info.id },
                    description = info.description.ifBlank { "插件技能" },
                    enabled = info.id in enabledPlugins,
                    dir = dir,
                )
            }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /** 已启用插件的快捷操作列表 */
    fun enabledActions(enabledPlugins: Set<String>): List<PluginAction> {        if (enabledPlugins.isEmpty()) return emptyList()
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
        val file = resolveWebResourceFile(pluginId, relativePath) ?: return null
        return runCatching { file.readText() }.getOrNull()
    }

    /**
     * 解析插件包内 web/ 目录下的资源文件（用于 webview 扩展入口以真实文件 URL 加载）。
     * relativePath 相对插件目录下的 web/，防路径穿越。
     */
    fun resolveWebResourceFile(pluginId: String, relativePath: String): File? {
        val webRoot = File(getPluginDir(pluginId), "web")
        val file = File(webRoot, relativePath)
        val canonicalRoot = runCatching { webRoot.canonicalPath }.getOrNull() ?: return null
        val canonicalFile = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (!canonicalFile.startsWith(canonicalRoot)) return null
        if (!file.isFile) return null
        return file
    }

    /** 内置插件包制作技能 id（随 App 预置，可在已安装列表卸载） */
    suspend fun ensureBuiltinSkill(): Boolean {
        val skillId = BUILTIN_PLUGIN_MAKER_ID
        if (getPluginDir(skillId).exists()) return false
        val bytes = runCatching { context.assets.open("plugin-maker-skill.zip").readBytes() }.getOrNull() ?: return false
        return runCatching { installZip(bytes) }.getOrNull()?.isSuccess == true
    }

    /** 从插件 zip 字节中提取 plugin.json（用于上传前校验与生成市场条目） */
    fun parseArchive(bytes: ByteArray): Result<PluginInfo> = Companion.extractPluginInfo(bytes)

    /** 解析插件目录内的 mcp.json，转换为 App 可用的 MCP 服务配置（启用 mcp 类型插件时注册）。递归查找，兼容子目录存放。 */
    fun mcpServersFromPlugin(pluginId: String): List<McpServerConfig> {
        val dir = getPluginDir(pluginId)
        val file = Companion.findFile(dir) {
            it.equals("mcp.json", ignoreCase = true) || it.equals(".mcp.json", ignoreCase = true)
        } ?: return emptyList()
        return runCatching { Companion.parseMcpServers(file) }.getOrDefault(emptyList())
    }

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
                // 缺少 plugin.json：尝试把角色卡/SKILL 技能/MCP 配置等资源包自动适配为本地插件
                val adapted = Companion.autoAdapt(staging)
                if (adapted == null) {
                    return@withContext Result.failure(
                        IllegalArgumentException("插件包缺少 plugin.json，且无法自动识别为 skill/MCP/角色卡资源包")
                    )
                }
                infoFile.writeText(PluginJson.toJson(adapted))
            } else {
                // 已有 plugin.json：补全缺失的能力提示词，保证第三方/收录包安装后真正生效
                Companion.ensurePluginJson(staging)
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
        const val BUILTIN_PLUGIN_MAKER_ID = "builtin-plugin-maker"
        private const val MAX_SYSTEM_PROMPT_LEN = 30000

        /**
         * 补全已有 plugin.json 的插件能力提示词：type=skill / character 但 systemPrompt 为空时，
         * 从包内递归查找 SKILL.md / 角色卡并写入 systemPrompt，使第三方收录包安装后真正生效。
         */
        fun ensurePluginJson(dir: File): Boolean {
            val infoFile = dir.resolve(METADATA_FILE)
            if (!infoFile.exists()) return false
            val info = runCatching { PluginJson.fromJson(infoFile.readText()) }.getOrNull() ?: return false
            if (info.systemPrompt.isNotBlank()) return false
            val prompt = when (info.type) {
                PluginCategories.TYPE_SKILL ->
                    findFile(dir) { it.equals("SKILL.md", ignoreCase = true) }
                        ?.readText()?.trim()
                PluginCategories.TYPE_CHARACTER ->
                    findFile(dir) {
                        it.endsWith(".card.json", ignoreCase = true) || it.equals("character.json", ignoreCase = true)
                    }?.let { parseCharacterJson(it.readText()) }?.systemPrompt
                else -> null
            }
            if (prompt.isNullOrBlank()) return false
            infoFile.writeText(PluginJson.toJson(info.copy(systemPrompt = prompt.take(MAX_SYSTEM_PROMPT_LEN))))
            return true
        }

        /**
         * 资源包自动适配：对解压目录中缺少 plugin.json 的内容做类型推导，
         * 生成最小的 RikkaHub 插件元数据，使角色卡/SKILL 技能/MCP 配置安装到本地即可生效。
         * 识别顺序：角色卡(JSON/PNG) > SKILL.md 技能 > mcp.json。无法识别返回 null。
         */
        fun autoAdapt(stagingDir: File): PluginInfo? {
            // 1. 角色卡：Tavern v3/v2 JSON 或 .card.png（tEXt chara）
            val cardFile = findFiles(stagingDir) {
                it.endsWith(".card.json", ignoreCase = true) ||
                    it.equals("character.json", ignoreCase = true)
            }.firstOrNull()
            if (cardFile != null) {
                runCatching { parseCharacterJson(cardFile.readText()) }
                    .getOrNull()?.let { return it }
            }
            val cardPng = findFiles(stagingDir) { it.endsWith(".card.png", ignoreCase = true) }.firstOrNull()
            if (cardPng != null) {
                runCatching { parseCharacterPng(cardPng) }.getOrNull()?.let { return it }
            }
            // 2. SKILL.md 技能
            findFile(stagingDir) { it.equals("SKILL.md", ignoreCase = true) }?.let { skillFile ->
                runCatching { parseSkillFile(skillFile) }.getOrNull()?.let { return it }
            }
            // 3. mcp.json / .mcp.json
            findFile(stagingDir) { it.equals("mcp.json", ignoreCase = true) || it.equals(".mcp.json", ignoreCase = true) }
                ?.let { mcpFile ->
                    val servers = runCatching { parseMcpServers(mcpFile) }.getOrDefault(emptyList())
                    if (servers.isNotEmpty()) {
                        val name = servers.first().commonOptions.name.ifBlank { "MCP" }
                        return PluginInfo(
                            id = resourceId("mcp-$name"),
                            name = "MCP: $name",
                            version = "1.0.0",
                            description = "MCP 服务 $name（共 ${servers.size} 个），启用后自动注册到 MCP 设置",
                            category = "mcp",
                            type = PluginCategories.TYPE_MCP,
                            tags = listOf(PluginCategories.TYPE_MCP),
                        )
                    }
                }
            return null
        }

        private fun parseCharacterJson(text: String): PluginInfo? {
            val root = Json.parseToJsonElement(text).jsonObject
            val data = root["data"]?.jsonObject ?: root
            val name = root["name"]?.jsonPrimitive?.contentOrNull
                ?: data["name"]?.jsonPrimitive?.contentOrNull
                ?: return null
            val description = root["description"]?.jsonPrimitive?.contentOrNull
                ?: data["description"]?.jsonPrimitive?.contentOrNull
                ?: "角色卡 $name"
            val systemPrompt = buildString {
                data["system_prompt"]?.jsonPrimitive?.contentOrNull?.let { append(it).append("\n\n") }
                root["description"]?.jsonPrimitive?.contentOrNull?.let { append("角色简介：").append(it).append("\n") }
                data["personality"]?.jsonPrimitive?.contentOrNull?.let { append("性格：").append(it).append("\n") }
                data["scenario"]?.jsonPrimitive?.contentOrNull?.let { append("场景：").append(it).append("\n") }
                data["first_mes"]?.jsonPrimitive?.contentOrNull?.let { append("开场白：").append(it) }
            }.trim()
            return PluginInfo(
                id = resourceId("character-$name"),
                name = name,
                version = "1.0.0",
                description = description,
                category = "character",
                type = PluginCategories.TYPE_CHARACTER,
                systemPrompt = systemPrompt,
                tags = listOf(PluginCategories.TYPE_CHARACTER),
            )
        }

        private fun parseCharacterPng(file: File): PluginInfo? {
            val bytes = file.readBytes()
            if (bytes.size < 8 || bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte()) return null
            var pos = 8
            while (pos + 12 <= bytes.size) {
                val len = readIntBE(bytes, pos)
                val type = String(bytes, pos + 4, 4)
                if (type == "IEND") break
                if (type == "tEXt" && pos + 8 + len <= bytes.size) {
                    val chunk = bytes.copyOfRange(pos + 8, pos + 8 + len)
                    val idx = chunk.indexOf(0)
                    if (idx > 0) {
                        val keyword = String(chunk, 0, idx, Charsets.ISO_8859_1)
                        if (keyword == "chara") {
                            val json = String(chunk, idx + 1, chunk.size - idx - 1, Charsets.ISO_8859_1)
                            return parseCharacterJson(json)
                        }
                    }
                }
                pos += 12 + len
            }
            return null
        }

        private fun parseSkillFile(skillFile: File): PluginInfo? {
            val content = skillFile.readText().trim()
            if (content.isBlank()) return null
            val frontmatter = parseFrontMatter(content)
            val name = frontmatter["name"]
                ?: skillFile.parentFile?.name?.ifBlank { null }
                ?: "Skill"
            val description = frontmatter["description"] ?: "技能 $name"
            return PluginInfo(
                id = resourceId("skill-$name"),
                name = name,
                version = "1.0.0",
                description = description,
                category = "skill",
                type = PluginCategories.TYPE_SKILL,
                systemPrompt = content.take(MAX_SYSTEM_PROMPT_LEN),
                tags = listOf(PluginCategories.TYPE_SKILL),
            )
        }

        private fun parseFrontMatter(content: String): Map<String, String> {
            if (!content.startsWith("---")) return emptyMap()
            val end = content.indexOf("\n---", 4)
            if (end < 0) return emptyMap()
            return content.substring(3, end).lines()
                .mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) null else line.substring(0, idx).trim() to
                        line.substring(idx + 1).trim().trim('"', '\'')
                }
                .toMap()
        }

        /** 解析 mcp.json / .mcp.json，根为 {"mcpServers": {...}} 或直接为服务名映射 */
        fun parseMcpServers(file: File): List<McpServerConfig> {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val servers = root["mcpServers"]?.jsonObject ?: root
            return servers.mapNotNull { (name, element) ->
                val obj = element.jsonObject
                val common = McpCommonOptions(enable = true, name = name)
                // type/transport 缺失时按字段推断：有 command 视为本地命令（Claude Code 标准 mcp.json
                // 只写 command/args，无 type 字段），否则有 url 视为远程服务
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                    ?: obj["transport"]?.jsonPrimitive?.contentOrNull
                    ?: when {
                        obj.containsKey("command") -> "command"
                        obj.containsKey("url") -> "sse"
                        else -> "sse"
                    }
                when (type.lowercase()) {
                    "sse", "http", "http-sse" -> {
                        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        McpServerConfig.SseTransportServer(commonOptions = common, url = url)
                    }
                    "streamable_http", "streamable-http", "http_streamable" -> {
                        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        McpServerConfig.StreamableHTTPServer(commonOptions = common, url = url)
                    }
                    "command", "stdio", "local", "npx" -> {
                        val command = obj["command"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val args = obj["args"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            ?: emptyList()
                        val envObj = obj["env"]?.jsonObject
                        val env = envObj?.entries?.mapNotNull { (k, v) ->
                            k to ((v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null)
                        }?.toMap() ?: emptyMap()
                        McpServerConfig.CommandServerConfig(
                            commonOptions = common,
                            command = command,
                            args = args,
                            env = env,
                        )
                    }
                    else -> null
                }
            }
        }

        /** 稳定的资源 id：ASCII slug + 短哈希，保证目录名安全且不冲突 */
        private fun resourceId(raw: String): String {
            val ascii = raw.lowercase().trim()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(40)
            val suffix = Integer.toHexString(raw.hashCode() and 0xffff)
            return "resource-${ascii.ifBlank { "res" }}-$suffix"
        }

        fun findFile(root: File, predicate: (String) -> Boolean): File? {
            return findFiles(root, predicate).firstOrNull()
        }

        private fun findFiles(root: File, predicate: (String) -> Boolean): List<File> {
            if (!root.isDirectory) return emptyList()
            val result = mutableListOf<File>()
            root.walkTopDown().forEach { file ->
                if (file.isFile && predicate(file.name)) result.add(file)
            }
            return result
        }

        private fun readIntBE(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xff) shl 24 or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
        }

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

/** 插件包内承载的 skill 型技能，供技能页与本地技能合并展示 */
data class PluginSkillInfo(
    val pluginId: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val dir: File,
)

object PluginJson {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun fromJson(text: String): PluginInfo = json.decodeFromString(PluginInfo.serializer(), text)

    fun toJson(info: PluginInfo): String = json.encodeToString(PluginInfo.serializer(), info)
}
