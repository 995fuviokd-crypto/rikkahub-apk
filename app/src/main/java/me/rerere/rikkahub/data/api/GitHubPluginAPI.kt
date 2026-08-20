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

private fun ByteArray.toBase64GitHub(): String = Base64.getEncoder().encodeToString(this)

/** 插件市场数据源：读取/写入索引，下载/上传插件 zip */
class PluginMarketDataSource(
    private val api: GitHubPluginAPI,
    private val httpClient: OkHttpClient,
) {
    /** 下载插件 zip 字节 */
    suspend fun downloadZip(url: String): ByteArray {
        val request = okhttp3.Request.Builder().url(url).build()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败: HTTP ${response.code}")
                response.body?.bytes() ?: error("下载失败: 空响应")
            }
        }
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

    private fun splitRepo(repo: String): Pair<String, String>? {
        val parts = repo.trim().trim('/').split('/')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return parts[0] to parts[1]
    }
}
