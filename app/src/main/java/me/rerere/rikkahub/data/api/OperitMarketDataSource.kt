package me.rerere.rikkahub.data.api

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.plugin.PluginCategories
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
 * Operit 社区市场数据源：静态市场（static.operit.app）为纯脚本/技能/MCP 资源库，
 * 条目 source 指向 GitHub 仓库/目录。App 内直接浏览其列表，安装时把 GitHub 目标目录
 * 打包为 RikkaHub 插件 zip（无 plugin.json 的资源经 [PluginManager.autoAdapt] 自动适配，
 * 保证安装到本地后真正生效）。
 */
@Serializable
data class OperitListItem(
    val type: String = "script",
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val detail: String = "",
    @SerialName("categoryId") val categoryId: String = "",
    @SerialName("stateCode") val stateCode: String = "",
    @SerialName("source") val source: OperitSource? = null,
    @SerialName("latestVersion") val latestVersion: OperitVersion = OperitVersion(),
    @SerialName("assets") val assets: List<OperitAsset> = emptyList(),
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
data class OperitAsset(
    val id: String = "",
    @SerialName("versionId") val versionId: String = "",
    val kind: String = "",
    val url: String = "",
    val sha256: String = "",
    @SerialName("assetName") val assetName: String = "",
)

/** author / publisher 字段在 Operit 不同版本中可能是字符串或对象（{id, login, avatar}），统一安全提取 */
private fun JsonElement?.toAuthorName(): String = when (this) {
    is JsonPrimitive -> content
    is JsonObject -> listOf("login", "name", "id")
        .mapNotNull { (this[it] as? JsonPrimitive)?.contentOrNull }
        .firstOrNull()
        .orEmpty()
    else -> ""
}

@Serializable
data class OperitSource(
    val kind: String = "",
    val url: String = "",
) {
    val repoOwner: String
        get() = OperitMarketDataSource.parseGitHubUrl(url)?.owner.orEmpty()

    val repoName: String
        get() = OperitMarketDataSource.parseGitHubUrl(url)?.repo.orEmpty()
}

@Serializable
data class OperitVersion(
    val id: String = "",
    val version: String = "",
    @SerialName("formatVer") val formatVer: String = "",
    @SerialName("minAppVer") val minAppVer: String = "",
    @SerialName("source") val source: OperitSource? = null,
)

@Serializable
data class OperitListResponse(
    val ok: Boolean = true,
    val total: Int = 0,
    val pageSize: Int = 100,
    val items: List<OperitListItem> = emptyList(),
)

interface OperitMarketApi {
    @GET("market/v2/lists/all/{sort}/page-{page}.json")
    suspend fun getAll(
        @Path("sort") sort: String,
        @Path("page") page: Int,
    ): OperitListResponse

    @GET("market/v2/lists/type/{type}/{sort}/page-{page}.json")
    suspend fun getByType(
        @Path("type") type: String,
        @Path("sort") sort: String,
        @Path("page") page: Int,
    ): OperitListResponse

    companion object {
        fun create(httpClient: OkHttpClient): OperitMarketApi {
            return Retrofit.Builder()
                .baseUrl("https://static.operit.app/")
                .client(httpClient)
                .addConverterFactory(
                    Json {
                        ignoreUnknownKeys = true
                        // Operit 各端点大量字段显式为 null（source/assets 等），按缺失处理走默认值
                        explicitNulls = false
                        coerceInputValues = true
                    }.asConverterFactory("application/json; charset=UTF8".toMediaType())
                )
                .build()
                .create(OperitMarketApi::class.java)
        }
    }
}

/**
 * Operit 市场数据源。
 * @param type 资源类型过滤：all / script / package / skill / mcp
 */
class OperitMarketDataSource(
    private val context: Context,
    private val api: OperitMarketApi,
    private val httpClient: OkHttpClient,
) {
    suspend fun fetchList(type: String?, sort: String, page: Int): Result<OperitListResponse> {
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
     * 将 Operit 条目下载并打包为 RikkaHub 插件 zip。按条目来源分流：
     * - skill / mcp：source 的 github_repo 目录 → codeload tarball → 解压 → 自动适配。
     * - script / package：source 为空，走 assets[].github_release_asset 直接下载
     *   （.js 脚本 / .toolpkg 运行包），解包识别后生成可安装插件。
     * 所有路径最终产出含有效 plugin.json 的 zip，避免「plugin.json 解析失败」。
     */
    suspend fun downloadAsPlugin(entry: OperitListItem): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            when (val handle = resolveOperitHandle(entry)) {
                is OperitSourceHandle.GitHubDir -> downloadGitHubDir(entry, handle.source)
                is OperitSourceHandle.ReleaseAsset -> downloadReleaseAsset(entry, handle)
            }
        }
    }

    private suspend fun downloadGitHubDir(entry: OperitListItem, parsed: GitHubSource): ByteArray {
        val tarball = downloadBytes(
            "https://codeload.github.com/${parsed.owner}/${parsed.repo}/tar.gz/${parsed.ref}"
        )
        val tempRoot = File(context.cacheDir, "operit-${System.nanoTime()}").apply { mkdirs() }
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
    private suspend fun downloadReleaseAsset(entry: OperitListItem, handle: OperitSourceHandle.ReleaseAsset): ByteArray {
        val bytes = downloadBytes(handle.url)
        val tempRoot = File(context.cacheDir, "operit-${System.nanoTime()}").apply { mkdirs() }
        return try {
            val outDir = tempRoot.resolve("plugin")
            when (detectOperitAssetFormat(bytes)) {
                OperitAssetFormat.ZIP -> buildToolpkgPlugin(outDir, entry, bytes, handle)
                OperitAssetFormat.GZIP -> buildGzipPlugin(outDir, entry, bytes, handle)
                OperitAssetFormat.TEXT -> buildScriptPlugin(outDir, entry, bytes, handle)
            }
            zipDirectory(outDir)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /** 解析 Operit 脚本头部 /* METADATA {…} */ 的 JSON 字段 */
    private fun buildToolpkgPlugin(
        outDir: File,
        entry: OperitListItem,
        bytes: ByteArray,
        handle: OperitSourceHandle.ReleaseAsset,
    ) {
        outDir.mkdirs()
        val rawDir = outDir.resolve("toolpkg").apply { mkdirs() }
        PluginManager.unzipTo(bytes, rawDir)
        val manifestFile = PluginManager.findFile(rawDir) { it.equals("manifest.json", ignoreCase = true) }
        val manifest = manifestFile
            ?.readText()
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val pkgName = jsonLocalizedString(manifest, "display_name", "name", "toolpkg_id")
            ?.takeIf { it.isNotBlank() }
            ?: entry.title.ifBlank { entry.id.substringAfter("package-") }
        val desc = jsonLocalizedString(manifest, "description") ?: entry.description
        val systemPrompt = buildString {
            append("这是来自 Operit 市场的 ToolPkg 工具包「$pkgName」。")
            if (desc.isNotBlank()) append("简介：$desc\n")
            append("原始包内容已保存在插件目录 toolpkg/ 下（manifest.json、main.js 等），")
            append("该工具包的运行依赖 Operit 运行时，RikkaHub 无法直接执行其中的 JS 逻辑；")
            append("你可读取插件目录下的文件内容，理解其能力定义后按需参考。")
        }
        writePluginInfo(
            outDir, entry, pkgName, "1.0.0", desc, systemPrompt,
            tags = listOf("operit", "toolpkg"),
        )
    }

    /** script（单 JS 文件）：解析头部 METADATA，生成说明型插件并保留脚本内容 */
    private fun buildScriptPlugin(
        outDir: File,
        entry: OperitListItem,
        bytes: ByteArray,
        handle: OperitSourceHandle.ReleaseAsset,
    ) {
        outDir.mkdirs()
        val scriptName = handle.assetName.ifBlank { entry.id.substringAfter("script-").ifBlank { "script.js" } }
        outDir.resolve(scriptName).writeBytes(bytes)
        val metadata = parseOperitScriptMetaObject(bytes)
        val name = jsonLocalizedString(metadata, "display_name", "name")
            ?: entry.title.ifBlank { scriptName.substringBeforeLast('.') }
        val desc = jsonLocalizedString(metadata, "description") ?: entry.description
        val systemPrompt = buildString {
            append("这是来自 Operit 市场的脚本「$name」。")
            if (desc.isNotBlank()) append("简介：$desc\n")
            append("脚本内容已保存在插件目录 $scriptName 中，该脚本依赖 Operit 运行时执行，")
            append("RikkaHub 无法直接运行；你可读取脚本文件了解其实现的工具能力，按需参考。")
        }
        writePluginInfo(
            outDir, entry, name, "1.0.0",
            desc.ifBlank { "来自 Operit 市场的脚本（$scriptName）" },
            systemPrompt,
            tags = listOf("operit", "script"),
        )
    }

    /** gzip 资产：可能是 tar.gz（继续 tar 解压）或 gzip 单文件，剥壳后按 zip/文本再处理 */
    private fun buildGzipPlugin(
        outDir: File,
        entry: OperitListItem,
        bytes: ByteArray,
        handle: OperitSourceHandle.ReleaseAsset,
    ) {
        val decompressed = try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } catch (_: Exception) {
            error("无法解压 gzip 资产：${handle.assetName}")
        }
        when (detectOperitAssetFormat(decompressed)) {
            OperitAssetFormat.ZIP -> buildToolpkgPlugin(outDir, entry, decompressed, handle)
            OperitAssetFormat.GZIP -> error("资产格式异常：${handle.assetName}")
            OperitAssetFormat.TEXT -> buildScriptPlugin(outDir, entry, decompressed, handle)
        }
    }

    /** 有 plugin.json 则补全 systemPrompt，否则走 autoAdapt；都无法识别时生成说明型插件兜底 */
    private fun ensureAdapted(dir: File, entry: OperitListItem) {
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
        val desc = entry.description.ifBlank { "来自 Operit 市场的资源（${typeLabel(entry.type)}）" }
        val fileList = dir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(dir).path }.toList()
        val systemPrompt = buildString {
            append("这是来自 Operit 市场的资源「$name」（${typeLabel(entry.type)}）。")
            if (desc.isNotBlank()) append("简介：$desc\n")
            append("未识别到 SKILL.md / mcp.json / 角色卡 等可注入内容，目录内容已原样保留，共 ")
            append(fileList.size).append(" 个文件，可按需读取参考。")
        }
        writePluginInfo(dir, entry, name, "1.0.0", desc, systemPrompt, tags = listOf("operit"))
    }

    /** 写 plugin.json + systemPrompt 文件，保证 zip 可被 RikkaHub 正常解析安装 */
    private fun writePluginInfo(
        dir: File,
        entry: OperitListItem,
        name: String,
        version: String,
        description: String,
        systemPrompt: String,
        tags: List<String>,
    ) {
        dir.mkdirs()
        val info = PluginInfo(
            id = operitPluginIdFor(entry.id.ifBlank { name }),
            name = name,
            version = version,
            description = description,
            author = entry.displayAuthor,
            repository = entry.source?.url.orEmpty(),
            category = "general",
            systemPrompt = systemPrompt,
            type = PluginCategories.TYPE_SKILL,
            tags = (listOf("operit") + tags).distinct(),
        )
        dir.resolve("plugin.json").writeText(PluginJson.toJson(info))
        dir.resolve("systemPrompt.md").writeText(systemPrompt)
    }

    private suspend fun downloadBytes(url: String): ByteArray {
        return withContext(Dispatchers.IO) {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.bytes() ?: error("空响应")
            }
        }
    }

    companion object {
        const val SORTS_JSON = "likes,downloads,updated"

        /** 资源类型在 Operit 中的中文名 */
        fun typeLabel(type: String): String = when (type) {
            "skill" -> "技能"
            "mcp" -> "MCP"
            "script" -> "脚本"
            "package" -> "ToolPkg"
            "all" -> "全部"
            else -> type
        }

        fun create(context: Context, httpClient: OkHttpClient): OperitMarketDataSource {
            return OperitMarketDataSource(context, OperitMarketApi.create(httpClient), httpClient)
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

/** Operit 条目内容来源：GitHub 目录（skill/mcp）或 release 资产（script/package） */
sealed class OperitSourceHandle {
    data class GitHubDir(val source: GitHubSource) : OperitSourceHandle()
    data class ReleaseAsset(val url: String, val assetName: String) : OperitSourceHandle()
}

/**
 * 解析 Operit 条目内容来源，优先级：latestVersion.source > entry.source > id 内嵌 GitHub 链接
 * > assets[].github_release_asset。skill/mcp 条目在列表接口带 source，而 script/package
 * 条目的 source 为 null，仅能通过 release 资产下载。
 */
internal fun resolveOperitHandle(entry: OperitListItem): OperitSourceHandle {
    val repoSource = listOf(entry.latestVersion.source, entry.source)
        .firstOrNull { it != null && it.kind == "github_repo" && it.url.isNotBlank() }
    if (repoSource != null) {
        val parsed = OperitMarketDataSource.parseGitHubUrl(repoSource.url)
            ?: error("无法解析 GitHub 链接：${repoSource.url}")
        return OperitSourceHandle.GitHubDir(parsed)
    }
    // 兜底：列表接口个别端点不带 source，但 id 内嵌了来源链接（如 skill-https-github-com-owner-repo-...）
    parseGitHubUrlFromId(entry.id)?.let { return OperitSourceHandle.GitHubDir(it) }
    // script/package：从 release 资产下载
    val asset = entry.assets.firstOrNull { it.kind == "github_release_asset" && it.url.isNotBlank() }
        ?: entry.assets.firstOrNull { it.url.isNotBlank() }
        ?: error("该条目未提供内容来源，无法安装")
    return OperitSourceHandle.ReleaseAsset(asset.url, asset.assetName)
}

internal enum class OperitAssetFormat { ZIP, GZIP, TEXT }

/** 资产字节格式检测：PK 魔数 → zip（toolpkg），gzip 魔数 → 压缩包，其余按文本（script） */
internal fun detectOperitAssetFormat(bytes: ByteArray): OperitAssetFormat = when {
    bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> OperitAssetFormat.ZIP
    bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() -> OperitAssetFormat.GZIP
    else -> OperitAssetFormat.TEXT
}

/** 解析 Operit 脚本头部 /* METADATA {…} */ 的 JSON 字段，字符串值去引号、对象值保留原文 */
internal fun parseOperitScriptMetadata(bytes: ByteArray): Map<String, String> {
    return extractOperitScriptMetadata(bytes)
        ?.let { raw ->
            runCatching {
                val obj = Json.parseToJsonElement(raw).jsonObject
                obj.entries.associate { (k, v) ->
                    k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
                }
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
}

/** 解析 Operit 脚本头部 METADATA 为 JsonObject，无法解析返回 null */
internal fun parseOperitScriptMetaObject(bytes: ByteArray): JsonObject? {
    return extractOperitScriptMetadata(bytes)
        ?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() }
}

/** 提取 Operit 脚本头部 /* METADATA {…} */ 的 JSON 原文 */
internal fun extractOperitScriptMetadata(bytes: ByteArray): String? {
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

/** 从 Operit 元数据（manifest.json / script METADATA）安全提取字符串字段：
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
internal fun operitPluginIdFor(raw: String): String {
    val ascii = raw.lowercase().trim()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
    val suffix = Integer.toHexString(raw.hashCode() and 0xffff)
    return "operit-${ascii.ifBlank { "res" }}-$suffix"
}
