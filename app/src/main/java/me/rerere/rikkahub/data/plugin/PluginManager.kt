package me.rerere.rikkahub.data.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.script.ScriptRuntime
import me.rerere.rikkahub.data.script.ScriptToolManifest
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

    /** 列出已安装插件，目录缺失 plugin.json 或解析失败的标记为损坏；第三方格式包尝试归一化自愈 */
    fun listPlugins(): List<InstalledPlugin> {
        return getPluginsDir().listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val infoFile = dir.resolve(METADATA_FILE)
                var info = if (infoFile.exists()) {
                    runCatching {
                        PluginJson.fromJson(infoFile.readText())
                    }.onFailure { Log.w(TAG, "parse plugin.json failed: ${dir.name}", it) }
                        .getOrNull()
                } else {
                    null
                }
                if (info == null && infoFile.exists()) {
                    // 旧版本安装的第三方格式包自愈：schema 归一化写回后重读，避免升级后仍显示损坏
                    info = runCatching {
                        Companion.normalizePluginJson(infoFile.readText(), dir)?.let { infoFile.writeText(it) }
                        PluginJson.fromJson(infoFile.readText())
                    }.onFailure { Log.w(TAG, "self-heal plugin.json failed: ${dir.name}", it) }
                        .getOrNull()
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
        // 目录前缀必须带分隔符，避免 "/x/web2" 误通过 "/x/web" 前缀校验
        if (canonicalFile != canonicalRoot && !canonicalFile.startsWith(canonicalRoot + File.separator)) return null
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
        val servers = runCatching { Companion.parseMcpServers(file) }.getOrDefault(emptyList())
        return servers.filter { it !is McpServerConfig.CommandServerConfig }
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
                    val commandOnlyMcp = Companion.findFile(staging) {
                        it.equals("mcp.json", ignoreCase = true) || it.equals(".mcp.json", ignoreCase = true)
                    }?.let { file ->
                        runCatching { Companion.parseMcpServers(file) }.getOrDefault(emptyList())
                            .filter { it !is McpServerConfig.CommandServerConfig }
                            .isEmpty()
                    } == true
                    val reason = if (commandOnlyMcp) {
                        "该包仅含本地命令型 MCP 服务（command/stdio），Android 端无法运行本地进程，" +
                            "请在其官方仓库部署为远程服务后通过「设置-MCP」添加"
                    } else {
                        "插件包缺少 plugin.json，且无法自动识别为 skill/MCP/角色卡资源包"
                    }
                    return@withContext Result.failure(IllegalArgumentException(reason))
                }
                infoFile.writeText(PluginJson.toJson(adapted))
            } else {
                // 已有 plugin.json：先做第三方 schema 归一化（Operit 原生格式等，安装即可用），
                // 再补全缺失的能力提示词，保证第三方/收录包安装后真正生效
                Companion.normalizePluginJson(infoFile.readText(), staging)?.let { infoFile.writeText(it) }
                Companion.ensurePluginJson(staging)
            }
            val info = runCatching { PluginJson.fromJson(infoFile.readText()) }
                .getOrElse { return@withContext Result.failure(IllegalArgumentException("plugin.json 解析失败")) }
            if (info.id.isBlank() || info.name.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("plugin.json 缺少 id 或 name"))
            }

            val targetDir = pluginsDir.resolve(info.id)
            // 旧版本备份，异常时回滚
            if (targetDir.exists()) {
                val backup = File(pluginsDir, ".backup_${info.id}_${System.nanoTime()}")
                try {
                    targetDir.copyRecursively(backup, overwrite = true)
                    targetDir.deleteRecursively()
                    if (!staging.renameTo(targetDir)) {
                        if (backup.exists()) {
                            targetDir.deleteRecursively()
                            backup.copyRecursively(targetDir, overwrite = true)
                            backup.deleteRecursively()
                        }
                        return@withContext Result.failure(IllegalStateException("安装插件失败"))
                    }
                    backup.deleteRecursively()
                } catch (e: Throwable) {
                    if (backup.exists()) backup.deleteRecursively()
                    if (!targetDir.exists() && staging.exists()) {
                        staging.renameTo(targetDir)
                    }
                    return@withContext Result.failure(IllegalStateException("插件备份/恢复失败", e))
                }
            } else {
                if (!staging.renameTo(targetDir)) {
                    return@withContext Result.failure(IllegalStateException("安装插件失败"))
                }
            }
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

        /** 插件 systemPrompt 最大长度 */
        const val MAX_SYSTEM_PROMPT_LEN = 30000

        /** 历史适配包中错误的脚本工具引用；app 内真实注册的脚本调用工具为 run_script_tool */
        private const val LEGACY_OPERIT_TOOL_REF = "run_operit_tool"
        private const val RUNNER_TOOL_REF = "run_script_tool"

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
         * 第三方插件包 schema 归一化：
         * 1. 标准包（可正常解析）：ignoreUnknownKeys 会静默丢弃蛇形命名等替代字段，仅在标准
         *    字段缺省时从 desc/summary/publisher/repo/system_prompt 等替代键补齐；未知的 type
         *    值按包内特征文件重新推断并生成能力提示词；另修正历史适配包中错误的工具引用。
         *    无需处理返回 null
         * 2. Operit 原生格式等非标准 plugin.json（package 字段当 id、web_path/sidebar 声明入口、
         *    缺 type/systemPrompt）：自动映射字段、推断类型、生成能力提示词与侧边栏入口，
         *    保证第三方市场收录包安装即可用，无需人工修补
         * 无法识别时返回 null，由调用方走原有失败路径。
         */
        fun normalizePluginJson(text: String, dir: File?): String? {
            val standard = runCatching { PluginJson.fromJson(text) }.getOrNull()
            if (standard != null) {
                var patched: PluginInfo = standard
                runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { root ->
                    fun alt(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
                        (root[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    if (patched.description.isBlank()) {
                        alt("desc", "summary", "long_description")?.let { patched = patched.copy(description = it) }
                    }
                    if (patched.author.isBlank()) {
                        alt("publisher")?.let { patched = patched.copy(author = it) }
                    }
                    if (patched.repository.isBlank()) {
                        alt("repo")?.let { patched = patched.copy(repository = it) }
                    }
                    if (patched.systemPrompt.isBlank()) {
                        alt("system_prompt")?.let { patched = patched.copy(systemPrompt = it.take(MAX_SYSTEM_PROMPT_LEN)) }
                    }
                    val knownType = patched.type == PluginCategories.TYPE_PLUGIN ||
                        patched.type == PluginCategories.TYPE_SKILL ||
                        patched.type == PluginCategories.TYPE_MCP ||
                        patched.type == PluginCategories.TYPE_JSON ||
                        patched.type == PluginCategories.TYPE_CHARACTER
                    if (!knownType) {
                        val resolved = resolveNormalizedType(patched.type, dir)
                        if (resolved != patched.type) {
                            patched = patched.copy(
                                type = resolved,
                                systemPrompt = patched.systemPrompt.ifBlank { buildNormalizedSystemPrompt(resolved, dir) },
                            )
                        }
                    }
                }
                if (!text.contains(LEGACY_OPERIT_TOOL_REF)) {
                    return if (patched != standard) PluginJson.toJson(patched) else null
                }
                return PluginJson.toJson(
                    patched.copy(
                        systemPrompt = patched.systemPrompt.replace(LEGACY_OPERIT_TOOL_REF, RUNNER_TOOL_REF),
                    )
                )
            }
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null

            fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
                (root[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }
            var rawId = str("id", "package", "packageName", "slug")
            val rawName = str("name", "title")
            val rawRepo = str("repository", "repo")
            if (rawId == null && rawName == null) {
                if (dir == null || dir.name.startsWith(".")) {
                    val repoId = rawRepo?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
                    if (repoId == null) return null
                    rawId = repoId
                }
            }
            val id = rawId ?: rawName?.let(::slugifyId) ?: dir!!.name
            val name = rawName ?: rawId ?: id
            val type = resolveNormalizedType(str("type"), dir)
            val tags = (root["tags"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { v -> v.isNotBlank() } }
                .orEmpty()
            val webPath = str("web_path", "webPath", "web")
            val sidebar = (root["sidebar"] as? JsonPrimitive)?.booleanOrNull == true
            return PluginJson.toJson(
                PluginInfo(
                    id = id,
                    name = name,
                    version = str("version") ?: "1.0.0",
                    description = str("description", "desc", "summary").orEmpty(),
                    author = str("author", "publisher").orEmpty(),
                    category = str("category") ?: "general",
                    repository = str("repository", "repo").orEmpty(),
                    systemPrompt = buildNormalizedSystemPrompt(type, dir),
                    type = type,
                    tags = tags,
                    extensionPoints = buildNormalizedSidebarEntry(id, name, webPath, sidebar, dir),
                )
            )
        }

        /** 推断插件资源类型：显式声明优先，其次按包内特征文件识别 */
        private fun resolveNormalizedType(raw: String?, dir: File?): String {
            when (raw) {
                null -> {}
                PluginCategories.TYPE_PLUGIN,
                PluginCategories.TYPE_SKILL,
                PluginCategories.TYPE_MCP,
                PluginCategories.TYPE_JSON,
                PluginCategories.TYPE_CHARACTER,
                -> return raw
            }
            if (dir != null) {
                if (findFile(dir) { it.equals("SKILL.md", ignoreCase = true) } != null) {
                    return PluginCategories.TYPE_SKILL
                }
                if (findFile(dir) {
                        it.equals("mcp.json", ignoreCase = true) || it.equals(".mcp.json", ignoreCase = true)
                    } != null
                ) {
                    return PluginCategories.TYPE_MCP
                }
                if (findFile(dir) {
                        it.endsWith(".card.json", ignoreCase = true) || it.equals("character.json", ignoreCase = true)
                    } != null
                ) {
                    return PluginCategories.TYPE_CHARACTER
                }
            }
            return PluginCategories.TYPE_PLUGIN
        }

        /** 归一化场景的能力提示词生成：skill 取 SKILL.md、角色卡取卡片内容、脚本型聚合 toolmanifest 工具清单 */
        private fun buildNormalizedSystemPrompt(type: String, dir: File?): String {
            if (dir == null) return ""
            return when (type) {
                PluginCategories.TYPE_SKILL ->
                    findFile(dir) { it.equals("SKILL.md", ignoreCase = true) }
                        ?.readText()?.trim()?.take(MAX_SYSTEM_PROMPT_LEN).orEmpty()
                PluginCategories.TYPE_CHARACTER ->
                    findFile(dir) {
                        it.endsWith(".card.json", ignoreCase = true) || it.equals("character.json", ignoreCase = true)
                    }?.let { runCatching { parseCharacterJson(it.readText()) }.getOrNull()?.systemPrompt }
                        .orEmpty()
                else -> describeNormalizedScriptTools(dir)
            }
        }

        /** 从 script|operit 目录的 toolmanifest.json（或脚本 METADATA 注释）生成工具能力提示词 */
        private fun describeNormalizedScriptTools(dir: File): String {
            val scriptDir = ScriptRuntime.scriptDir(dir)
            if (!scriptDir.isDirectory) return ""
            val manifest = scriptDir.resolve(ScriptRuntime.TOOL_MANIFEST).takeIf { it.isFile }
                ?.let { runCatching { ScriptToolManifest.parseJson(it.readText()) }.getOrNull() }
            val tools = manifest?.tools?.takeIf { it.isNotEmpty() }
                ?: ScriptToolManifest.toolsFromDirectory(scriptDir)
            if (tools.isEmpty()) return ""
            return buildString {
                appendLine("该插件提供以下脚本工具，可在对话中通过 `$RUNNER_TOOL_REF` 按需调用（需在助手工具设置中开启「脚本」）：")
                tools.forEach { tool ->
                    append("- ").append(tool.name)
                    if (tool.description.isNotBlank()) append("：").append(tool.description)
                    appendLine()
                }
            }.trim()
        }

        /**
         * Operit 原生入口声明转侧边栏 webview 扩展点：
         * web_path 相对包根（如 web/index.html），payload 转为 plugin://<id>/<web目录内相对路径>
         */
        private fun buildNormalizedSidebarEntry(
            id: String,
            name: String,
            webPath: String?,
            sidebar: Boolean,
            dir: File?,
        ): PluginExtensionPoints {
            if (!sidebar && webPath.isNullOrBlank()) return PluginExtensionPoints()
            val relative = webPath?.trim('/')?.removePrefix("web/")?.takeIf { it.isNotBlank() }
                ?: dir?.let { File(it, "web/index.html") }?.takeIf { it.isFile }?.let { "index.html" }
                ?: return PluginExtensionPoints()
            return PluginExtensionPoints(
                sidebarActions = listOf(
                    PluginExtensionAction(
                        id = "${id}_panel",
                        label = name,
                        target = "webview",
                        payload = "plugin://$id/$relative",
                    )
                )
            )
        }

        /** 中文名转安全 id：ASCII slug 化，全中文等无法 slug 时退化为短哈希 */
        private fun slugifyId(raw: String): String {
            val ascii = raw.lowercase().trim()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(40)
            return ascii.ifBlank { "plugin-${Integer.toHexString(raw.hashCode() and 0xffff)}" }
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
            // 3. mcp.json / .mcp.json（过滤 Android 端不可用的 command 类型，只保留远程服务）
            findFile(stagingDir) { it.equals("mcp.json", ignoreCase = true) || it.equals(".mcp.json", ignoreCase = true) }
                ?.let { mcpFile ->
                    val servers = runCatching { parseMcpServers(mcpFile) }.getOrDefault(emptyList())
                        .filter { it !is McpServerConfig.CommandServerConfig }
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
            // 4. 脚本资源包：script/ 或 operit/ 目录含 .js 脚本 → 生成本地可执行的脚本插件
            runCatching { adaptScriptPackage(stagingDir) }.getOrNull()?.let { return it }
            return null
        }

        /** 脚本目录资源包识别：从 toolmanifest.json / 脚本 METADATA 注释聚合工具清单 */
        private fun adaptScriptPackage(stagingDir: File): PluginInfo? {
            val scriptDir = ScriptRuntime.scriptDir(stagingDir)
            if (!scriptDir.isDirectory) return null
            if (scriptDir.listFiles()?.any { it.extension == "js" } != true) return null
            val manifest = scriptDir.resolve(ScriptRuntime.TOOL_MANIFEST).takeIf { it.isFile }
                ?.let { runCatching { ScriptToolManifest.parseJson(it.readText()) }.getOrNull() }
            val tools = manifest?.tools?.takeIf { it.isNotEmpty() }
                ?: ScriptToolManifest.toolsFromDirectory(scriptDir)
            if (tools.isEmpty()) return null
            val name = manifest?.name?.takeIf { it.isNotBlank() } ?: "脚本资源包"
            val description = manifest?.description?.takeIf { it.isNotBlank() }
                ?: "本地脚本资源包，含 ${tools.size} 个可调用工具"
            return PluginInfo(
                id = resourceId("script-$name"),
                name = name,
                version = "1.0.0",
                description = description,
                category = "automation",
                type = PluginCategories.TYPE_PLUGIN,
                systemPrompt = ScriptToolManifest.describeSystemPrompt(name, description, tools, source = "本地"),
            )
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
                val text = jsonBytes.toString(Charsets.UTF_8)
                runCatching { PluginJson.fromJson(text) }.getOrElse {
                    // 第三方 schema（Operit 原生格式等）容错：归一化后再解析
                    PluginJson.fromJson(normalizePluginJson(text, null) ?: error("plugin.json 解析失败"))
                }
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
) {
    /** 技能来源分组（防止不同渠道安装混在一个列表里难以区分） */
    val source: SkillSource
        get() = when {
            pluginId.startsWith("dsh-") -> SkillSource.DSH_MARKET
            pluginId.startsWith("community-") || pluginId.startsWith("operit-") -> SkillSource.COMMUNITY_MARKET
            else -> SkillSource.OFFICIAL_OR_LOCAL
        }
}

enum class SkillSource(val label: String, val hint: String) {
    OFFICIAL_OR_LOCAL("官方市场 / 本地导入", "来自官方插件市场或手动导入"),
    COMMUNITY_MARKET("社区市场", "来自 Operit 社区资源库"),
    DSH_MARKET("DSH 市场", "来自 DeepSeek Harness 插件生态"),
}

object PluginJson {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun fromJson(text: String): PluginInfo = json.decodeFromString(PluginInfo.serializer(), text)

    fun toJson(info: PluginInfo): String = json.encodeToString(PluginInfo.serializer(), info)
}
