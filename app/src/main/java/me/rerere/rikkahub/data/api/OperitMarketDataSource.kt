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
import kotlinx.serialization.json.jsonPrimitive
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
    @SerialName("source") val source: OperitSource = OperitSource(),
    @SerialName("latestVersion") val latestVersion: OperitVersion = OperitVersion(),
    val author: JsonElement? = null,
    val publisher: JsonElement? = null,
) {
    val displayAuthor: String
        get() = source.repoOwner.ifBlank {
            author.toAuthorName().ifBlank { publisher.toAuthorName() }
        }

    val sourceKind: String get() = source.kind
}

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
    @SerialName("source") val source: OperitSource = OperitSource(),
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
                    Json { ignoreUnknownKeys = true }
                        .asConverterFactory("application/json; charset=UTF8".toMediaType())
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
     * 将 Operit 条目对应的 GitHub 目录下载并打包为 RikkaHub 插件 zip。
     * 步骤：解析 source.url → codeload 拉 tarball → tar.gz 解压 → 裁剪到目标子目录 →
     * 补全 plugin.json（autoAdapt / ensurePluginJson）→ 打包 zip。
     */
    suspend fun downloadAsPlugin(entry: OperitListItem): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val src = entry.latestVersion.source.takeIf { it.kind == "github_repo" }
                ?: entry.source.takeIf { it.kind == "github_repo" }
                ?: error("该条目不是 GitHub 仓库来源，无法安装")
            val parsed = parseGitHubUrl(src.url) ?: error("无法解析 GitHub 链接：${src.url}")
            val tarball = downloadBytes(
                "https://codeload.github.com/${parsed.owner}/${parsed.repo}/tar.gz/${parsed.ref}"
            )
            val tempRoot = File(context.cacheDir, "operit-${System.nanoTime()}").apply { mkdirs() }
            try {
                extractTarGz(tarball, tempRoot)
                val repoRoot = locateRoot(tempRoot, parsed.repo)
                val sourceDir = if (parsed.path.isNullOrBlank()) {
                    repoRoot
                } else {
                    repoRoot.resolve(parsed.path).takeIf { it.isDirectory }
                        ?: error("仓库中不存在目录：${parsed.path}")
                }
                if (!hasAnyResource(sourceDir)) {
                    error("该目录下未找到 skill / mcp / 角色卡 / plugin.json 资源")
                }
                if (!ensureAdapted(sourceDir)) {
                    error("资源包无法自动适配为 RikkaHub 插件")
                }
                zipDirectory(sourceDir)
            } finally {
                tempRoot.deleteRecursively()
            }
        }
    }

    private fun hasAnyResource(dir: File): Boolean {
        if (dir.resolve("plugin.json").isFile) return true
        val found = PluginManager.findFile(dir) {
            it.equals("SKILL.md", ignoreCase = true) ||
                it.equals("mcp.json", ignoreCase = true) ||
                it.equals(".mcp.json", ignoreCase = true) ||
                it.endsWith(".card.json", ignoreCase = true) ||
                it.equals("character.json", ignoreCase = true)
        }
        return found != null
    }

    /** 有 plugin.json 则补全 systemPrompt，否则走 autoAdapt 生成 */
    private fun ensureAdapted(dir: File): Boolean {
        val infoFile = dir.resolve("plugin.json")
        if (infoFile.exists()) {
            PluginManager.ensurePluginJson(dir)
            return true
        }
        val adapted = PluginManager.autoAdapt(dir) ?: return false
        infoFile.writeText(PluginJson.toJson(adapted))
        return true
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
