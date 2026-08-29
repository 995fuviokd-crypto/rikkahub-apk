package me.rerere.rikkahub.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/** 市场仓库 tavern.json 中的一条角色卡条目 */
@Serializable
data class TavernListing(
    val id: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val emoji: String = "🎭",
    /** 卡片文件路径：仓库相对路径或完整 https 直链，空则视为纯展示占位 */
    val file: String = "",
    /** 资产类型：card=角色卡 worldbook=世界书 preset=预设 regex=正则脚本 */
    val type: String = "card",
)

/**
 * 酒馆角色卡市场数据源：与插件市场共用同一个 GitHub 仓库，
 * 索引为根目录 tavern.json，卡片文件放 tavern/ 目录。
 * raw 直连失败自动降级镜像（复用 [MarketHttp]）。
 */
class TavernMarketDataSource(
    private val httpClient: OkHttpClient,
) {

    /** 索引候选：依次尝试，直到一个可用（支持 .gz 自动解压） */
    suspend fun fetchDefault(): Result<List<TavernListing>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var lastError: Throwable? = null
            for (url in defaultIndexUrls) {
                try {
                    return@withContext Result.success(parseIndexJson(decodeBytes(download(url))))
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            Result.failure(lastError ?: IllegalStateException("所有索引源均不可用"))
        }

    /** gz 自动解压为 UTF-8 文本 */
    private fun decodeBytes(bytes: ByteArray): String {
        if (MarketHttp.isGzipArchive(bytes)) {
            java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
                .also { return it.toString(Charsets.UTF_8) }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /** 下载字节；GitHub 域名自动附加速镜像 */
    private suspend fun download(url: String): ByteArray =
        MarketHttp.downloadFirstAvailable(httpClient, url, timeoutMs = 30_000)

    /** 下载卡片文件字节（JSON 或 PNG），支持仓库相对路径与完整直链 */
    suspend fun downloadCard(repo: String, file: String): Result<ByteArray> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val path = file.trim().trimStart('/')
                require(path.isNotBlank()) { "条目缺少文件路径" }
                if (path.startsWith("http://") || path.startsWith("https://")) {
                    return@runCatching download(path)
                }
                val parts = repo.trim().trim('/').split('/')
                download("${parts[0]}/${parts[1]}/main/$path")
            }
        }

    companion object {
        private val json = MarketHttp.json

        /** 酒馆索引自动降级候选：市场仓库 → 公共直链（gzip） */
        val defaultIndexUrls = listOf(
            "https://raw.githubusercontent.com/995fuviokd-crypto/plugin-market/main/tavern.json.gz",
            "https://gh-proxy.com/https://raw.githubusercontent.com/995fuviokd-crypto/plugin-market/main/tavern.json.gz",
            "https://github.com/995fuviokd-crypto/plugin-market/raw/main/tavern.json.gz",
        )

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
                    type = obj["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "card",
                )
            }
        }
    }
}
