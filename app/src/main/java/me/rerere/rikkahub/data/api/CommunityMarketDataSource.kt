package me.rerere.rikkahub.data.api

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.script.ScriptRuntime
import me.rerere.rikkahub.data.script.ScriptToolManifest
import me.rerere.rikkahub.data.script.ScriptToolManifestData
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginExtensionAction
import me.rerere.rikkahub.data.plugin.PluginExtensionPoints
import me.rerere.rikkahub.data.plugin.PluginInfo
import me.rerere.rikkahub.data.plugin.PluginJson
import me.rerere.rikkahub.data.plugin.PluginManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 社区市场数据源：基于静态市场（static.operit.app）的纯脚本/技能/MCP 资源库，
 * 条目 source 指向 GitHub 仓库/目录。App 内直接浏览其列表，安装时把 GitHub 目标目录
 * 打包为 RikkaHub 插件 zip（无 plugin.json 的资源经 [PluginManager.autoAdapt] 自动适配，
 * 保证安装到本地后真正生效）。
 */
@Serializable
data class CommunityListItem(
    val type: String = "script",
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val detail: String = "",
    @SerialName("categoryId") val categoryId: String = "",
    @SerialName("stateCode") val stateCode: String = "",
    @SerialName("source") val source: CommunitySource? = null,
    @SerialName("latestVersion") val latestVersion: CommunityVersion = CommunityVersion(),
    @SerialName("assets") val assets: List<CommunityAsset> = emptyList(),
    val author: JsonElement? = null,
    val publisher: JsonElement? = null,
) {
    val displayAuthor: String
        get() = source?.repoOwner.orEmpty().ifBlank {
            author.toAuthorName().ifBlank { publisher.toAuthorName() }
        }

    val sourceKind: String get() = source?.kind.orEmpty()
}

/** release 资产：script/package 类型无 GitHub 目录来源，改由该下载地址获取 */
@Serializable
data class CommunityAsset(
    val id: String = "",
    @SerialName("versionId") val versionId: String = "",
    val kind: String = "",
    val url: String = "",
    val sha256: String = "",
    @SerialName("assetName") val assetName: String = "",
)

/** author / publisher 字段在社区市场数据不同版本中可能是字符串或对象（{id, login, avatar}），统一安全提取 */
private fun JsonElement?.toAuthorName(): String = when (this) {
    is JsonPrimitive -> content
    is JsonObject -> listOf("login", "name", "id")
        .mapNotNull { (this[it] as? JsonPrimitive)?.contentOrNull }
        .firstOrNull()
        .orEmpty()
    else -> ""
}

@Serializable
data class CommunitySource(
    val kind: String = "",
    val url: String = "",
) {
    val repoOwner: String
        get() = CommunityMarketDataSource.parseGitHubUrl(url)?.owner.orEmpty()

    val repoName: String
        get() = CommunityMarketDataSource.parseGitHubUrl(url)?.repo.orEmpty()
}

@Serializable
data class CommunityVersion(
    val id: String = "",
    val version: String = "",
    @SerialName("formatVer") val formatVer: String = "",
    @SerialName("minAppVer") val minAppVer: String = "",
    @SerialName("source") val source: CommunitySource? = null,
)

@Serializable
data class CommunityListResponse(
    val ok: Boolean = true,
    val total: Int = 0,
    val pageSize: Int = 100,
    val items: List<CommunityListItem> = emptyList(),
)

interface CommunityMarketApi {
    @GET("market/v2/lists/all/{sort}/page-{page}.json")
    suspend fun getAll(
        @Path("sort") sort: String,
        @Path("page") page: Int,
    ): CommunityListResponse

    @GET("market/v2/lists/type/{type}/{sort}/page-{page}.json")
    suspend fun getByType(
        @Path("type") type: String,
        @Path("sort") sort: String,
        @Path("page") page: Int,
    ): CommunityListResponse

    companion object {
        fun create(httpClient: OkHttpClient): CommunityMarketApi {
            return Retrofit.Builder()
                .baseUrl("https://static.operit.app/")
                .client(httpClient)
                .addConverterFactory(
                    Json {
                        ignoreUnknownKeys = true
                        // 社区市场各端点大量字段显式为 null（source/assets 等），按缺失处理走默认值
                        explicitNulls = false
                        coerceInputValues = true
                    }.asConverterFactory("application/json; charset=UTF8".toMediaType())
                )
                .build()
                .create(CommunityMarketApi::class.java)
        }
    }
}

/**
 * 社区市场数据源。
 * @param type 资源类型过滤：all / script / package / skill / mcp
 */
class CommunityMarketDataSource(
    private val context: Context,
    private val api: CommunityMarketApi,
    private val httpClient: OkHttpClient,
) {
    suspend fun fetchList(type: String?, sort: String, page: Int): Result<CommunityListResponse> {
        return runCatching {
            val response = if (type.isNullOrBlank() || type == "all") {
                api.getAll(sort, page)
            } else {
                api.getByType(type, sort, page)
            }
            if (!response.ok) error("市场响应异常")
            response
        }
    }

    /**
     * 将社区市场条目下载并打包为 RikkaHub 插件 zip。按条目来源分流：
     * - skill / mcp：source 的 github_repo 目录 → codeload tarball → 解压 → 自动适配。
     * - script / package：source 为空，走 assets[].github_release_asset 直接下载
     *   （.js 脚本 / .toolpkg 运行包），解包识别后生成可安装插件。
     * 所有路径最终产出含有效 plugin.json 的 zip，避免「plugin.json 解析失败」。
     */
    suspend fun downloadAsPlugin(entry: CommunityListItem): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            when (val handle = resolveCommunityHandle(entry)) {
                is CommunitySourceHandle.GitHubDir -> downloadGitHubDir(entry, handle.source)
                is CommunitySourceHandle.ReleaseAsset -> downloadReleaseAsset(entry, handle)
            }
        }
    }

    private suspend fun downloadGitHubDir(entry: CommunityListItem, parsed: GitHubSource): ByteArray {
        val tarball = downloadBytes(
            "https://codeload.github.com/${parsed.owner}/${parsed.repo}/tar.gz/${parsed.ref}"
        )
        val tempRoot = File(context.cacheDir, "community-${System.nanoTime()}").apply { mkdirs() }
        return try {
            extractTarGz(tarball, tempRoot)
            val repoRoot = locateRoot(tempRoot, parsed.repo)
            val sourceDir = if (parsed.path.isNullOrBlank()) {
                repoRoot
            } else {
                repoRoot.resolve(parsed.path).takeIf { it.isDirectory }
                    ?: error("仓库中不存在目录：${parsed.path}")
            }
            ensureAdapted(sourceDir, entry)
            zipDirectory(sourceDir)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /** release 资产通道：script（单 JS）/ package（.toolpkg zip）下载后转成可安装插件 */
    private suspend fun downloadReleaseAsset(entry: CommunityListItem, handle: CommunitySourceHandle.ReleaseAsset): ByteArray {
        val bytes = downloadBytes(handle.url)
        val tempRoot = File(context.cacheDir, "community-${System.nanoTime()}").apply { mkdirs() }
        return try {
            val outDir = tempRoot.resolve("plugin")
            when (detectCommunityAssetFormat(bytes)) {
                CommunityAssetFormat.ZIP -> buildToolpkgPlugin(outDir, entry, bytes, handle)
                CommunityAssetFormat.GZIP -> buildGzipPlugin(outDir, entry, bytes, handle)
                CommunityAssetFormat.TEXT -> buildScriptPlugin(outDir, entry, bytes, handle)
            }
            zipDirectory(outDir)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /** 解析 脚本头部 /* METADATA {…} */ 的 JSON 字段 */
    private fun buildToolpkgPlugin(
        outDir: File,
        entry: CommunityListItem,
        bytes: ByteArray,
        handle: CommunitySourceHandle.ReleaseAsset,
    ) {
        outDir.mkdirs()
        val scriptDir = outDir.resolve(ScriptRuntime.SCRIPT_DIR).apply { mkdirs() }
        PluginManager.unzipTo(bytes, scriptDir)
        // 部分 toolpkg 顶层嵌套单目录，拍平到脚本目录下
        scriptDir.listFiles()?.singleOrNull { it.isDirectory }?.let { nested ->
            nested.copyRecursively(scriptDir, overwrite = true)
            nested.deleteRecursively()
        }
        val manifestFile = PluginManager.findFile(scriptDir) { it.equals("manifest.json", ignoreCase = true) }
        val manifest = manifestFile
            ?.readText()
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val pkgName = jsonLocalizedString(manifest, "display_name", "name", "toolpkg_id")
            ?.takeIf { it.isNotBlank() }
            ?: entry.title.ifBlank { entry.id.substringAfter("package-") }
        val desc = jsonLocalizedString(manifest, "description") ?: entry.description
        val version = (manifest?.get("version") as? JsonPrimitive)?.contentOrNull
            ?: "1.0.0"
        val tools = ScriptToolManifest.toolsFromDirectory(scriptDir)
        val systemPrompt = ScriptToolManifest.describeSystemPrompt(pkgName, desc, tools)
        writePluginInfo(
            outDir, entry, pkgName, version, desc, systemPrompt,
            tags = listOf("community", "toolpkg"),
        )
        scriptDir.resolve(ScriptRuntime.TOOL_MANIFEST).writeText(
            ScriptToolManifest.buildJson(ScriptToolManifestData(pkgName, desc, tools))
        )
        generateIndexHtml(
            outDir, pkgName, desc.ifBlank { "来自 社区市场的 ToolPkg 资源包" },
            tools = tools, kind = "package",
            fileList = scriptDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(scriptDir).path }.toList(),
        )
    }

    /** script（单 JS 文件）：解析头部 METADATA，生成本地可执行的脚本插件并保留脚本内容 */
    private fun buildScriptPlugin(
        outDir: File,
        entry: CommunityListItem,
        bytes: ByteArray,
        handle: CommunitySourceHandle.ReleaseAsset,
    ) {
        outDir.mkdirs()
        val scriptDir = outDir.resolve(ScriptRuntime.SCRIPT_DIR).apply { mkdirs() }
        val scriptName = handle.assetName.ifBlank { entry.id.substringAfter("script-").ifBlank { "script.js" } }
        scriptDir.resolve(scriptName).writeBytes(bytes)
        val metadata = parseScriptMetaObject(bytes)
        val name = jsonLocalizedString(metadata, "display_name", "name")
            ?: entry.title.ifBlank { scriptName.substringBeforeLast('.') }
        val desc = jsonLocalizedString(metadata, "description") ?: entry.description
        val tools = ScriptToolManifest.toolsFromMetadata(bytes)
        val systemPrompt = ScriptToolManifest.describeSystemPrompt(name, desc, tools)
        writePluginInfo(
            outDir, entry, name, "1.0.0",
            desc.ifBlank { "来自 社区市场的脚本（$scriptName）" },
            systemPrompt,
            tags = listOf("community", "script"),
        )
        scriptDir.resolve(ScriptRuntime.TOOL_MANIFEST).writeText(
            ScriptToolManifest.buildJson(ScriptToolManifestData(name, desc, tools))
        )
        generateIndexHtml(
            outDir, name, desc.ifBlank { "来自 社区市场的脚本（$scriptName）" },
            tools = tools, kind = "script",
        )
    }

    /** gzip 资产：可能是 tar.gz（继续 tar 解压）或 gzip 单文件，剥壳后按 zip/文本再处理 */
    private fun buildGzipPlugin(
        outDir: File,
        entry: CommunityListItem,
        bytes: ByteArray,
        handle: CommunitySourceHandle.ReleaseAsset,
    ) {
        val decompressed = try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } catch (_: Exception) {
            error("无法解压 gzip 资产：${handle.assetName}")
        }
        when (detectCommunityAssetFormat(decompressed)) {
            CommunityAssetFormat.ZIP -> buildToolpkgPlugin(outDir, entry, decompressed, handle)
            CommunityAssetFormat.GZIP -> error("资产格式异常：${handle.assetName}")
            CommunityAssetFormat.TEXT -> buildScriptPlugin(outDir, entry, decompressed, handle)
        }
    }

    /** 有 plugin.json 则补全 systemPrompt，否则走 autoAdapt；都无法识别时生成说明型插件兜底 */
    private fun ensureAdapted(dir: File, entry: CommunityListItem) {
        val infoFile = dir.resolve("plugin.json")
        if (infoFile.exists()) {
            // 第三方自带 plugin.json 字段未必兼容 RikkaHub 格式，解析失败则忽略并重新适配
            val compatible = runCatching {
                val info = PluginJson.fromJson(infoFile.readText())
                info.id.isNotBlank() && info.name.isNotBlank()
            }.getOrDefault(false)
            if (compatible) {
                PluginManager.ensurePluginJson(dir)
                return
            }
            infoFile.delete()
        }
        PluginManager.autoAdapt(dir)?.let { adapted ->
            infoFile.writeText(PluginJson.toJson(adapted))
            return
        }
        // 兜底：仓库目录无任何可识别资源（如普通 MCP 源码仓库），保留内容并生成说明型插件
        val name = entry.title.ifBlank { entry.id }
        val desc = entry.description.ifBlank { "来自 社区市场的资源（${typeLabel(entry.type)}）" }
        val fileList = dir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(dir).path }.toList()
        // 源码型 MCP 条目：优先从仓库识别启动命令生成 mcp.json，使其可注册到 MCP 设置
        val isMcpSource = entry.type == "mcp"
        if (isMcpSource && generateMcpConfigIfPossible(dir, entry, name)) {
            PluginManager.autoAdapt(dir)?.let { adapted ->
                infoFile.writeText(PluginJson.toJson(adapted))
                return
            }
        }
        val systemPrompt = buildString {
            append("这是来自 社区市场的资源「$name」（${typeLabel(entry.type)}）。")
            if (desc.isNotBlank()) append("简介：$desc\n")
            if (isMcpSource) {
                append("这是一个 MCP Server 源码仓库，需要 Node 等外部运行环境编译/运行，RikkaHub 无法直接连接。")
                append("目录内容已原样保留（共 ${fileList.size} 个文件），可参考其实现，或自行搭建服务后通过「设置-MCP」添加远程服务。")
            } else {
                append("未识别到 SKILL.md / mcp.json / 角色卡 等可注入内容，目录内容已原样保留，共 ")
                append(fileList.size).append(" 个文件，可按需读取参考。")
            }
        }
        writePluginInfo(
            dir, entry, name, "1.0.0", desc, systemPrompt,
            tags = listOf("community", if (isMcpSource) PluginCategories.TYPE_MCP else "other"),
            type = if (isMcpSource) PluginCategories.TYPE_MCP else PluginCategories.TYPE_PLUGIN,
            category = if (isMcpSource) "mcp" else "general",
        )
        // 生成 web 索引展示页（含文件清单），供应用内 webview 展示
        generateIndexHtml(
            dir, name, desc,
            tools = emptyList(),
            kind = if (isMcpSource) "mcp" else "resource",
            fileList = fileList,
        )
    }

    /**
     * 从 MCP 源码仓库识别可执行配置：依次尝试 mcp.json（已存在）、smithery.yaml、
     * package.json（bin/scripts.start/main）、README 中的 npx/uvx 启动命令。
     * 命中后写入 mcp.json（Claude Code 兼容），使插件安装后可注册到 MCP 设置。
     * @return 是否成功生成 mcp.json
     */
    private fun generateMcpConfigIfPossible(dir: File, entry: CommunityListItem, fallbackName: String): Boolean {
        val existing = PluginManager.findFile(dir) {
            it.equals("mcp.json", ignoreCase = true) || it.equals(".mcp.json", ignoreCase = true)
        }
        if (existing != null) return true
        val servers = mutableListOf<Pair<String, List<String>>>()
        PluginManager.findFile(dir) { it.equals("smithery.yaml", ignoreCase = true) || it.equals("smithery.yml", ignoreCase = true) }
            ?.let { servers.addAll(parseSmitheryCommand(it.readText())) }
        if (servers.isEmpty()) {
            PluginManager.findFile(dir) { it.equals("package.json", ignoreCase = true) }
                ?.let { parsePackageJsonCommand(it.readText())?.let { c -> servers.add(c) } }
        }
        if (servers.isEmpty()) {
            PluginManager.findFile(dir) {
                it.matches(Regex("(?i)readme(\\.md|\\.txt|\\.markdown)?$"))
            }?.let { parseReadmeRunCommand(it.readText())?.let { c -> servers.add(c) } }
        }
        if (servers.isEmpty()) return false
        val safeName = fallbackName.replace(Regex("[^a-zA-Z0-9._-]"), "-").trim('-').ifBlank { "community-mcp" }
        val mcpJson = buildJsonObject {
            put("mcpServers", buildJsonObject {
                servers.take(3).forEachIndexed { index, (cmd, args) ->
                    val serverName = if (index == 0) safeName.take(40) else "$safeName-${index + 1}"
                    put(serverName, buildJsonObject {
                        put("type", "command")
                        put("command", cmd)
                        put("args", JsonArray(args.map { JsonPrimitive(it) }))
                    })
                }
            })
        }
        runCatching { dir.resolve("mcp.json").writeText(mcpJson.toString()) }
        return true
    }

    /** 解析 smithery.yaml 中的 startCommand.command 列表 */
    private fun parseSmitheryCommand(content: String): List<Pair<String, List<String>>> {
        val cmd = Regex("""(?m)^\s*command:\s*$""").find(content) ?: return emptyList()
        val rest = content.substring(cmd.range.last)
        val args = Regex("""(?m)^\s*-\s*(.+?)\s*$""").findAll(rest)
            .map { it.groupValues[1].trim().trim('"', '\'', '`') }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(16)
            .toList()
        if (args.isEmpty()) return emptyList()
        return listOf(args.first() to args.drop(1))
    }

    /** 解析 package.json：优先 bin 入口（node 执行），其次 scripts.start，最后 main */
    private fun parsePackageJsonCommand(content: String): Pair<String, List<String>>? {
        val root = runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
        (root["bin"]?.jsonObject?.values?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.let { bin ->
            return "node" to listOf(bin)
        }
        ((root["bin"] as? JsonPrimitive)?.contentOrNull)?.let { bin ->
            return "node" to listOf(bin)
        }
        (root["scripts"]?.jsonObject?.get("start") as? JsonPrimitive)?.contentOrNull?.let { start ->
            val parts = start.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.isNotEmpty()) return parts.first() to parts.drop(1)
        }
        (root["main"] as? JsonPrimitive)?.contentOrNull?.let { main ->
            return "node" to listOf(main)
        }
        return null
    }

    /** 解析 README 中的 npx/uvx 启动命令 */
    private fun parseReadmeRunCommand(content: String): Pair<String, List<String>>? {
        val regex = Regex("""(npx|uvx|node)\s+([@\w][\w@./-]*)((?:[\s-]+[@\w./-][\w@./-]*)*)""")
        val m = regex.find(content) ?: return null
        val runner = m.groupValues[1]
        val pkg = m.groupValues[2]
        val tail = m.groupValues[3].trim().split(Regex("\\s+")).filter { it.isNotBlank() && it != "-y" }
        return (runner to (listOf("-y", pkg) + tail))
    }

    /** 生成应用内 webview 可展示的插件索引页（web/index.html），含名称/描述/工具清单/文件列表 */
    private fun generateIndexHtml(
        dir: File,
        title: String,
        desc: String,
        tools: List<me.rerere.rikkahub.data.script.ScriptToolDef>,
        kind: String,
        fileList: List<String> = emptyList(),
    ) {
        val webDir = File(dir, "web").apply { mkdirs() }
        val toolItems = if (tools.isEmpty()) {
            "<li>（无独立工具清单）</li>"
        } else {
            tools.joinToString("") { t ->
                val d = t.description.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                "<li><code>${htmlEsc(t.name)}</code>${if (d.isNotBlank()) " — $d" else ""}</li>"
            }
        }
        val fileItems = if (fileList.isEmpty()) {
            ""
        } else {
            "<h3>包含文件</h3><ul>" + fileList.sorted().take(100).joinToString("") { "<li><code>${htmlEsc(it)}</code></li>" } +
                (if (fileList.size > 100) "<li>…共 ${fileList.size} 个</li>" else "") + "</ul>"
        }
        val kindLabel = when (kind) {
            "script" -> "脚本"
            "package" -> "社区 ToolPkg"
            "mcp" -> "社区 MCP"
            else -> "资源包"
        }
        webDir.resolve("index.html").writeText(
            """
            <!doctype html><html lang="zh"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${htmlEsc(title)}</title>
            <style>
            body{font-family:system-ui,sans-serif;margin:0;padding:16px;background:#f6f7f9;color:#1c1c1e;line-height:1.6}
            h1{font-size:20px;margin:0 0 4px}h2{font-size:15px;margin:18px 0 6px}
            .badge{display:inline-block;font-size:12px;padding:2px 8px;border-radius:10px;background:#e4e9f0;color:#3a4557;margin-bottom:8px}
            .desc{font-size:14px;color:#3a4557;margin:0 0 8px}
            ul{padding-left:18px;font-size:14px}code{background:#eef1f5;padding:1px 5px;border-radius:4px;font-size:12px}
            .note{font-size:12px;color:#8a919c;background:#fff;border:1px solid #e6e8ec;border-radius:8px;padding:10px;margin-top:16px}
            </style>
            <h1>${htmlEsc(title)}</h1><span class="badge">$kindLabel</span>
            <p class="desc">${htmlEsc(desc)}</p>
            <h2>可用工具</h2><ul>$toolItems</ul>
            $fileItems
            <div class="note">该资源由 社区市场提供，已由 RikkaHub 本地引擎适配。脚本依赖的 Tools.* 运行时
            （文件/网络/系统通知等）已映射为 RikkaHub 本地能力；依赖 UI 自动化、浏览器控制等特权能力的工具会返回受限提示。</div>
            </html>
            """.trimIndent()
        )
    }

    private fun htmlEsc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** 写 plugin.json + systemPrompt 文件，保证 zip 可被 RikkaHub 正常解析安装 */
    private fun writePluginInfo(
        dir: File,
        entry: CommunityListItem,
        name: String,
        version: String,
        description: String,
        systemPrompt: String,
        tags: List<String>,
        type: String = PluginCategories.TYPE_SKILL,
        category: String = "general",
    ) {
        dir.mkdirs()
        val id = communityPluginIdFor(entry.id.ifBlank { name })
        // 自动生成 web 展示扩展入口：包内含 web/ 目录 → 插件内置页面；条目带 GitHub 来源 → 源码仓库页
        val homeActions = buildList {
            val webHasHtml = File(dir, "web").listFiles()?.any {
                it.isFile && it.name.endsWith(".html", ignoreCase = true)
            } == true
            if (webHasHtml) {
                add(
                    PluginExtensionAction(
                        id = "web-home",
                        label = "打开页面",
                        description = "打开插件内置页面",
                        target = "webview",
                        payload = "plugin://$id/index.html",
                    )
                )
            }
            val repoUrl = entry.source?.url.orEmpty()
            if (repoUrl.startsWith("http://") || repoUrl.startsWith("https://")) {
                add(
                    PluginExtensionAction(
                        id = "repo-link",
                        label = "查看源码仓库",
                        description = "在应用内打开条目源码仓库",
                        target = "webview",
                        payload = repoUrl,
                    )
                )
            }
        }
        val info = PluginInfo(
            id = id,
            name = name,
            version = version,
            description = description,
            author = entry.displayAuthor,
            repository = entry.source?.url.orEmpty(),
            category = category,
            systemPrompt = systemPrompt,
            type = type,
            tags = (listOf("community") + tags).distinct(),
            extensionPoints = PluginExtensionPoints(homeActions = homeActions),
        )
        dir.resolve("plugin.json").writeText(PluginJson.toJson(info))
        dir.resolve("systemPrompt.md").writeText(systemPrompt)
    }

    private suspend fun downloadBytes(url: String): ByteArray {
        return withContext(Dispatchers.IO) {
            // GitHub 资源直连失败（被墙/超时）时自动切换镜像加速源重试
            val candidates = if (isGitHubUrl(url)) {
                listOf(url) + PluginMarketDataSource.MIRROR_PREFIXES.map { it + url }
            } else {
                listOf(url)
            }
            var lastError: Throwable? = null
            for (candidate in candidates) {
                try {
                    val bytes = httpClient.newCall(Request.Builder().url(candidate).build()).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        response.body?.bytes() ?: error("空响应")
                    }
                    return@withContext bytes
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("Download failed: $url")
        }
    }

    private fun isGitHubUrl(url: String): Boolean {
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return false
        return host.endsWith("github.com") || host.endsWith("githubusercontent.com")
    }

    companion object {
        const val SORTS_JSON = "likes,downloads,updated"

        /** 资源类型在社区市场中的中文名 */
        fun typeLabel(type: String): String = when (type) {
            "skill" -> "技能"
            "mcp" -> "MCP"
            "script" -> "脚本"
            "package" -> "ToolPkg"
            "all" -> "全部"
            else -> type
        }

        fun create(context: Context, httpClient: OkHttpClient): CommunityMarketDataSource {
            return CommunityMarketDataSource(context, CommunityMarketApi.create(httpClient), httpClient)
        }

        /** 解析 GitHub 链接为 owner/repo/ref/path（支持 tree、blob、根仓库链接） */
        fun parseGitHubUrl(url: String): GitHubSource? {
            val u = url.trim().trimEnd('/').removeSuffix(".git")
            if (!u.startsWith("https://github.com/")) return null
            val parts = u.removePrefix("https://github.com/").split('/')
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            var idx = 2
            var ref = "main"
            var path: String? = null
            if (parts.size > idx && parts[idx] in setOf("tree", "blob", "raw")) {
                idx++
                if (parts.size > idx && parts[idx].isNotBlank()) ref = parts[idx]
                idx++
                path = parts.drop(idx).joinToString("/").ifBlank { null }
                if (path != null) path = path.trimEnd('/')
                // blob 指向单个文件时取其父目录
                if (parts[2] == "blob" || parts[2] == "raw") {
                    path = path?.substringBeforeLast('/')?.ifBlank { null }
                }
            }
            return GitHubSource(parts[0], parts[1], ref, path)
        }

        /** tar.gz 解压到目录（codeload 使用标准 tar，兼容 GNU longname 块与 pax 跳过） */
        fun extractTarGz(bytes: ByteArray, targetDir: File) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
                val header = ByteArray(512)
                while (true) {
                    if (!readFully(gz, header)) break
                    if (header.all { it == 0.toByte() }) break
                    var name = String(header, 0, 100).trimEnd('\u0000')
                    val size = parseOctal(header, 124)
                    val type = header[156].toInt().toChar()
                    when (type) {
                        'L' -> name = readStringBlock(gz, size)
                        'x', 'g' -> skip(gz, size)
                        '5' -> {
                            File(targetDir, sanitize(name)).mkdirs()
                            skip(gz, size)
                        }
                        else -> {
                            if (name.isNotBlank()) {
                                val target = File(targetDir, sanitize(name))
                                if (type == '0' || type == '\u0000' || type == '7') {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { out -> copyBlock(gz, size, out) }
                                } else {
                                    skip(gz, size)
                                }
                            } else {
                                skip(gz, size)
                            }
                        }
                    }
                }
            }
        }

        /** 目录打包为 zip 字节（zip 根目录为目录内容） */
        fun zipDirectory(dir: File): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                val files = dir.walkTopDown().filter { it.isFile }.toList()
                for (file in files) {
                    val relative = file.relativeTo(dir).path.replace('\\', '/')
                    zos.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            return bos.toByteArray()
        }

        /** 定位解压根目录：优先 {repo}-{ref}，否则取唯一顶层目录 */
        fun locateRoot(tempRoot: File, repo: String): File {
            val dirs = tempRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (dirs.size == 1) return dirs[0]
            dirs.firstOrNull { it.name.startsWith(repo) }?.let { return it }
            return dirs.firstOrNull() ?: tempRoot
        }

        private fun readStringBlock(gz: GZIPInputStream, size: Long): String {
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = gz.read(bytes, offset, bytes.size - offset)
                if (read < 0) break
                offset += read
            }
            skipPad(gz, size)
            return String(bytes).trimEnd('\u0000')
        }

        private fun copyBlock(gz: GZIPInputStream, size: Long, out: OutputStream) {
            val buffer = ByteArray(8192)
            var remaining = size
            while (remaining > 0) {
                val read = gz.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            skipPad(gz, size)
        }

        private fun skip(gz: GZIPInputStream, size: Long) {
            var remaining = size
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val read = gz.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read
            }
            skipPad(gz, size)
        }

        private fun skipPad(gz: GZIPInputStream, size: Long) {
            val pad = (512 - (size % 512)) % 512
            if (pad > 0) gz.skip(pad)
        }

        private fun readFully(input: InputStream, bytes: ByteArray): Boolean {
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) {
                    if (offset == 0) return false
                    break
                }
                offset += read
            }
            return offset == bytes.size
        }

        private fun parseOctal(header: ByteArray, offset: Int): Long {
            var result = 0L
            for (i in offset until minOf(offset + 12, header.size)) {
                val c = header[i].toInt()
                if (c in '0'.code..'7'.code) result = (result shl 3) or (c - '0'.code).toLong()
            }
            return result
        }

        private fun sanitize(name: String): String {
            return name.split('/').fold(StringBuilder()) { acc, part ->
                if (part == ".." || part == "." || part.isBlank()) {
                    if (acc.isNotEmpty()) acc.append('/')
                } else {
                    if (acc.isNotEmpty() && acc.last() != '/') acc.append('/')
                    acc.append(part)
                }
                acc
            }.toString()
        }
    }
}

data class GitHubSource(
    val owner: String,
    val repo: String,
    val ref: String,
    val path: String?,
)

/** 社区市场条目内容来源：GitHub 目录（skill/mcp）或 release 资产（script/package） */
sealed class CommunitySourceHandle {
    data class GitHubDir(val source: GitHubSource) : CommunitySourceHandle()
    data class ReleaseAsset(val url: String, val assetName: String) : CommunitySourceHandle()
}

/**
 * 解析社区市场条目内容来源，优先级：latestVersion.source > entry.source > id 内嵌 GitHub 链接
 * > assets[].github_release_asset。skill/mcp 条目在列表接口带 source，而 script/package
 * 条目的 source 为 null，仅能通过 release 资产下载。
 */
internal fun resolveCommunityHandle(entry: CommunityListItem): CommunitySourceHandle {
    val repoSource = listOf(entry.latestVersion.source, entry.source)
        .firstOrNull { it != null && it.kind == "github_repo" && it.url.isNotBlank() }
    if (repoSource != null) {
        val parsed = CommunityMarketDataSource.parseGitHubUrl(repoSource.url)
            ?: error("无法解析 GitHub 链接：${repoSource.url}")
        return CommunitySourceHandle.GitHubDir(parsed)
    }
    // 兜底：列表接口个别端点不带 source，但 id 内嵌了来源链接（如 skill-https-github-com-owner-repo-...）
    parseGitHubUrlFromId(entry.id)?.let { return CommunitySourceHandle.GitHubDir(it) }
    // script/package：从 release 资产下载
    val asset = entry.assets.firstOrNull { it.kind == "github_release_asset" && it.url.isNotBlank() }
        ?: entry.assets.firstOrNull { it.url.isNotBlank() }
        ?: error("该条目未提供内容来源，无法安装")
    return CommunitySourceHandle.ReleaseAsset(asset.url, asset.assetName)
}

internal enum class CommunityAssetFormat { ZIP, GZIP, TEXT }

/** 资产字节格式检测：PK 魔数 → zip（toolpkg），gzip 魔数 → 压缩包，其余按文本（script） */
internal fun detectCommunityAssetFormat(bytes: ByteArray): CommunityAssetFormat = when {
    bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> CommunityAssetFormat.ZIP
    bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() -> CommunityAssetFormat.GZIP
    else -> CommunityAssetFormat.TEXT
}

/** 解析 脚本头部 /* METADATA {…} */ 的 JSON 字段，字符串值去引号、对象值保留原文 */
internal fun parseScriptMetadata(bytes: ByteArray): Map<String, String> {
    return extractScriptMetadata(bytes)
        ?.let { raw ->
            runCatching {
                val obj = Json.parseToJsonElement(raw).jsonObject
                obj.entries.associate { (k, v) ->
                    k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
                }
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
}

/** 解析 脚本头部 METADATA 为 JsonObject，无法解析返回 null */
internal fun parseScriptMetaObject(bytes: ByteArray): JsonObject? {
    return extractScriptMetadata(bytes)
        ?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() }
}

/** 提取 脚本头部 /* METADATA {…} */ 的 JSON 原文 */
internal fun extractScriptMetadata(bytes: ByteArray): String? {
    val head = String(bytes, 0, minOf(bytes.size, 8192), Charsets.UTF_8)
    val start = head.indexOf("METADATA")
    if (start < 0) return null
    val brace = head.indexOf('{', start)
    if (brace < 0) return null
    var depth = 0
    var end = -1
    for (i in brace until head.length) {
        when (head[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) { end = i + 1; break }
            }
        }
    }
    if (end < 0) return null
    return head.substring(brace, end)
}

/** 从社区市场元数据（manifest.json / script METADATA）安全提取字符串字段：
 *  支持多语言对象 {zh,en,name} 与纯字符串，避免 JsonObject 被当 JsonPrimitive 崩溃 */
internal fun jsonLocalizedString(obj: JsonObject?, vararg keys: String): String? {
    for (key in keys) {
        val v = obj?.get(key) ?: continue
        val s = when (v) {
            is JsonPrimitive -> v.contentOrNull
            is JsonObject -> listOf("zh", "en")
                .mapNotNull { (v[it] as? JsonPrimitive)?.contentOrNull }
                .firstOrNull { it.isNotBlank() }
                ?: (v["name"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        if (!s.isNullOrBlank()) return s
    }
    return null
}

/** 从条目 id 内嵌的 slug 化链接还原 owner/repo（如 skill-https-github-com-owner-repo-...）。
 *  owner 取首个不含连字符的段，repo 取其余（含连字符），子路径因分隔符被替换无法还原。
 *  仅作列表接口缺 source 时的兜底。 */
internal fun parseGitHubUrlFromId(id: String): GitHubSource? {
    val match = Regex("https-github-com-([a-z0-9_.]+)-([a-z0-9_.-]+)").find(id) ?: return null
    val owner = match.groupValues[1]
    val repo = match.groupValues[2]
    if (owner.isBlank() || repo.isBlank()) return null
    return GitHubSource(owner, repo, "main", null)
}

/** 稳定插件 id：ascii slug + 短哈希，保证目录名安全且不冲突 */
internal fun communityPluginIdFor(raw: String): String {
    val ascii = raw.lowercase().trim()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
    val suffix = Integer.toHexString(raw.hashCode() and 0xffff)
    return "community-${ascii.ifBlank { "res" }}-$suffix"
}
