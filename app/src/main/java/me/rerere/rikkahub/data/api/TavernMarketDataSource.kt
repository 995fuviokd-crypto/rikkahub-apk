package me.rerere.rikkahub.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import androidx.core.net.toUri

/** 市场仓库 tavern.json 中的一条角色卡条目 */
@Serializable
data class TavernListing(
    val id: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val emoji: String = "🎭",
    /** 卡片文件相对仓库根路径（.json 或 .png），空则视为纯展示占位 */
    val file: String = "",
)

/**
 * 酒馆角色卡市场数据源：与插件市场共用同一个 GitHub 仓库，
 * 索引为根目录 tavern.json，卡片文件放 tavern/ 目录。
 * raw 直连失败自动降级镜像。
 */
class TavernMarketDataSource(
    private val httpClient: OkHttpClient,
) {

    private fun candidates(repoPath: String): List<String> = listOf(
        "https://raw.githubusercontent.com/$repoPath",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$repoPath",
        "https://ghfast.top/https://raw.githubusercontent.com/$repoPath",
    )

    private fun download(url: String): ByteArray {
        val client = httpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.bytes() ?: error("空响应")
        }
    }

    private fun fetchViaMirrors(repoPath: String): String {
        var lastError: Throwable? = null
        for (candidate in candidates(repoPath)) {
            try {
                return download(candidate).toString(Charsets.UTF_8)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("拉取失败")
    }

    /** 拉取 tavern.json 索引 */
    suspend fun fetchIndex(repo: String): Result<List<TavernListing>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val parts = repo.trim().trim('/').split('/')
                if (parts.size != 2 || parts.any { it.isBlank() }) error("仓库格式应为 owner/repo")
                parseIndexJson(fetchViaMirrors("${parts[0]}/${parts[1]}/main/tavern.json"))
            }
        }

    /** 下载卡片文件字节（JSON 或 PNG） */
    suspend fun downloadCard(repo: String, file: String): Result<ByteArray> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val parts = repo.trim().trim('/').split('/')
                val path = file.trimStart('/')
                require(path.isNotBlank()) { "条目缺少文件路径" }
                var lastError: Throwable? = null
                for (candidate in candidates("${parts[0]}/${parts[1]}/main/$path")) {
                    try {
                        return@runCatching download(candidate)
                    } catch (e: Throwable) {
                        lastError = e
                    }
                }
                throw lastError ?: IllegalStateException("下载失败")
            }
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        internal fun parseIndexJson(text: String): List<TavernListing> {
            val array = json.parseToJsonElement(text).jsonArray
            return array.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TavernListing(
                    id = id,
                    name = name,
                    description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                    emoji = obj["emoji"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "🎭",
                    file = obj["file"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }
    }
}
