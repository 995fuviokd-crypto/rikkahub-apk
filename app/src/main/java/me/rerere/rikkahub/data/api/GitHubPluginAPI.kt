package me.rerere.rikkahub.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.plugin.PluginMarketEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.Base64

/**
 * GitHub 插件市场 API：索引仓库根目录维护 plugins.json（插件列表），插件 zip 放在
 * plugins/<id>-<version>.zip。未登录可读（浏览/下载），上传需要 PAT。
 */
interface GitHubPluginAPI {
    /** 获取仓库根目录下的 plugins.json 原始内容（raw 响应为裸 JSON，用 ResponseBody 手动读取避免 kotlinx 字符串解码） */
    @GET("/repos/{owner}/{repo}/contents/plugins.json")
    suspend fun getPluginIndex(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Accept") accept: String = "application/vnd.github.raw+json",
    ): ResponseBody

    /** 获取仓库根目录下的 submissions.json 原始内容（提交审核队列） */
    @GET("/repos/{owner}/{repo}/contents/submissions.json")
    suspend fun getSubmissions(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Accept") accept: String = "application/vnd.github.raw+json",
    ): ResponseBody

    /** 获取当前 Token 对应的 GitHub 用户 */
    @GET("/user")
    suspend fun getUser(
        @Header("Authorization") authorization: String,
    ): GitHubUser

    /** 获取仓库内插件 zip 的元数据（含 sha 与 download_url） */
    @GET("/repos/{owner}/{repo}/contents/{path}")
    suspend fun getContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Header("Authorization") authorization: String? = null,
    ): GitHubContent

    /** 提交/更新插件 zip 或索引文件 */
    @PUT("/repos/{owner}/{repo}/contents/{path}")
    suspend fun uploadContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Header("Authorization") authorization: String,
        @Body body: GitHubCommitBody,
    ): GitHubCommitResponse

    companion object {
        fun create(httpClient: OkHttpClient): GitHubPluginAPI {
            return Retrofit.Builder()
                .baseUrl("https://api.github.com/")
                .client(httpClient)
                .addConverterFactory(
                    Json {
                        ignoreUnknownKeys = true
                    }.asConverterFactory("application/json; charset=UTF8".toMediaType())
                )
                .build()
                .create(GitHubPluginAPI::class.java)
        }
    }
}

@Serializable
data class GitHubContent(
    val name: String = "",
    val sha: String = "",
    val size: Int = 0,
    val download_url: String? = null,
)

@Serializable
data class GitHubUser(
    val login: String = "",
    val id: Long = 0,
)

/** 提交审核队列条目（submissions.json）。status: pending / approved / rejected */
@Serializable
data class SubmissionEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val repository: String = "",
    val category: String = "general",
    val type: String = "plugin",
    val tags: List<String> = emptyList(),
    val fileName: String = "",
    val downloadUrl: String = "",
    val status: String = "pending",
    val submitter: String = "",
    val submittedAt: String = "",
    val reviewedAt: String? = null,
    val reviewNote: String? = null,
)

@Serializable
data class GitHubCommitBody(
    val message: String,
    val content: String,
    val sha: String? = null,
)

@Serializable
data class GitHubCommitResponse(
    val content: GitHubContent? = null,
)

private val marketJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val marketEntrySerializer: KSerializer<List<PluginMarketEntry>> =
    ListSerializer(PluginMarketEntry.serializer())

private val submissionEntrySerializer: KSerializer<List<SubmissionEntry>> =
    ListSerializer(SubmissionEntry.serializer())

private fun ByteArray.toBase64GitHub(): String = Base64.getEncoder().encodeToString(this)

/** 插件市场数据源：读取/写入索引，下载/上传插件 zip */
class PluginMarketDataSource(
    private val api: GitHubPluginAPI,
    private val httpClient: OkHttpClient,
) {
    /** 下载插件 zip 字节。优先原地址，失败或内容异常时自动切换镜像加速源。 */
    suspend fun downloadZip(url: String): ByteArray {
        val candidates = buildList {
            add(url)
            MIRROR_PREFIXES.forEach { mirror ->
                add(mirror + url)
            }
        }
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    httpClient.newCall(okhttp3.Request.Builder().url(candidate).build()).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        response.body?.bytes() ?: error("空响应")
                    }
                }
                if (!bytes.isZipArchive()) {
                    lastError = IllegalArgumentException("下载内容不是有效的插件包（zip 头校验失败），已尝试镜像源")
                    continue
                }
                return bytes
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    suspend fun parseIndex(repo: String): Result<List<PluginMarketEntry>> {
        val (owner, repoName) = splitRepo(repo)
            ?: return Result.failure(IllegalArgumentException("仓库格式应为 owner/repo"))
        return runCatching {
            val raw = api.getPluginIndex(owner, repoName).string()
            marketJson.decodeFromString(marketEntrySerializer, raw)
        }
    }

    /**
     * 上传插件 zip 到用户仓库 plugins/ 目录，同时更新 plugins.json 索引。
     * 返回上传后的浏览器访问链接。
     */
    suspend fun uploadPlugin(
        token: String,
        repo: String,
        zipFileName: String,
        zipBytes: ByteArray,
        entry: PluginMarketEntry,
    ): Result<String> {
        val (owner, repoName) = splitRepo(repo)
            ?: return Result.failure(IllegalArgumentException("仓库格式应为 owner/repo"))
        val auth = "Bearer $token"
        return runCatching {
            val zipPath = "plugins/$zipFileName"
            val existingZip = try {
                api.getContent(owner, repoName, zipPath, auth).sha
            } catch (e: Throwable) {
                null
            }
            api.uploadContent(
                owner, repoName, zipPath, auth,
                GitHubCommitBody(
                    message = "Add plugin ${entry.name} ${entry.version}",
                    content = zipBytes.toBase64GitHub(),
                    sha = existingZip,
                ),
            )

            // 更新 plugins.json 索引：读现有 -> 合并 -> 写回
            val existingIndex = try {
                api.getPluginIndex(owner, repoName).string()
            } catch (e: Throwable) {
                "[]"
            }
            val current = runCatching {
                marketJson.decodeFromString(marketEntrySerializer, existingIndex)
            }.getOrDefault(emptyList())
            val updated = current.filterNot { it.id == entry.id } + entry
            val indexJson = marketJson.encodeToString(marketEntrySerializer, updated)
            val existingIndexSha = try {
                api.getContent(owner, repoName, "plugins.json", auth).sha
            } catch (e: Throwable) {
                null
            }
            api.uploadContent(
                owner, repoName, "plugins.json", auth,
                GitHubCommitBody(
                    message = "Update plugin index for ${entry.name}",
                    content = indexJson.toByteArray().toBase64GitHub(),
                    sha = existingIndexSha,
                ),
            )
            "https://github.com/$owner/$repoName/blob/main/plugins/$zipFileName"
        }
    }

    /**
     * 提交插件到市场仓库的 submissions/ 待审核目录，并在 submissions.json 中登记
     * status=pending 条目。管理员在审批软件中审核通过后才会写入 plugins/ 与 plugins.json 上架。
     * 返回提交记录链接。
     */
    suspend fun submitPlugin(
        token: String,
        repo: String,
        zipFileName: String,
        zipBytes: ByteArray,
        entry: PluginMarketEntry,
    ): Result<String> {
        val (owner, repoName) = splitRepo(repo)
            ?: return Result.failure(IllegalArgumentException("仓库格式应为 owner/repo"))
        val auth = "Bearer $token"
        return runCatching {
            val submitter = try {
                api.getUser(auth).login
            } catch (e: Throwable) {
                ""
            }

            // 1. 上传插件包到 submissions/<id>/ 目录
            val zipPath = "submissions/${entry.id}/$zipFileName"
            val existingZip = try {
                api.getContent(owner, repoName, zipPath, auth).sha
            } catch (e: Throwable) {
                null
            }
            api.uploadContent(
                owner, repoName, zipPath, auth,
                GitHubCommitBody(
                    message = "Submit plugin ${entry.name} ${entry.version} for review",
                    content = zipBytes.toBase64GitHub(),
                    sha = existingZip,
                ),
            )

            // 2. 在 submissions.json 登记 pending 条目
            val existingSubs = try {
                api.getSubmissions(owner, repoName).string()
            } catch (e: Throwable) {
                "[]"
            }
            val currentSubs = runCatching {
                marketJson.decodeFromString(submissionEntrySerializer, existingSubs)
            }.getOrDefault(emptyList())
            val newSub = SubmissionEntry(
                id = entry.id,
                name = entry.name,
                version = entry.version,
                description = entry.description,
                author = entry.author,
                repository = entry.repository,
                category = entry.category,
                type = entry.type,
                tags = entry.tags,
                fileName = zipFileName,
                downloadUrl = "https://github.com/$owner/$repoName/raw/main/submissions/${entry.id}/$zipFileName",
                status = "pending",
                submitter = submitter,
                submittedAt = System.currentTimeMillis().toString(),
            )
            val updatedSubs = currentSubs.filterNot {
                it.id == entry.id && it.status == "pending"
            } + newSub
            val subJson = marketJson.encodeToString(submissionEntrySerializer, updatedSubs)
            val existingSubsSha = try {
                api.getContent(owner, repoName, "submissions.json", auth).sha
            } catch (e: Throwable) {
                null
            }
            api.uploadContent(
                owner, repoName, "submissions.json", auth,
                GitHubCommitBody(
                    message = "Register submission ${entry.name} (${entry.version})",
                    content = subJson.toByteArray().toBase64GitHub(),
                    sha = existingSubsSha,
                ),
            )
            "https://github.com/$owner/$repoName/blob/main/submissions.json"
        }
    }

    private fun splitRepo(repo: String): Pair<String, String>? {
        val parts = repo.trim().trim('/').split('/')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return parts[0] to parts[1]
    }

    companion object {
        /** GitHub 加速镜像前缀（按顺序尝试，域名失效可替换） */
        val MIRROR_PREFIXES = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
        )
    }
}

/** 判断字节是否为 zip 包（PK 文件头） */
private fun ByteArray.isZipArchive(): Boolean = size >= 4 &&
    this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte()
