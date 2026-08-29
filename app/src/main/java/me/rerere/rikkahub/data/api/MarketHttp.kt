package me.rerere.rikkahub.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 市场数据源公共网络工具：统一 JSON 实例、GitHub 加速镜像、候选 URL 重试与字节魔数判断。
 * 四个市场数据源（官方/社区/DSH/酒馆）共用，消除各自重复实现。
 */
internal object MarketHttp {
    /** 统一 JSON 实例：忽略未知键，缺失字段走默认值 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** GitHub 加速镜像前缀（按顺序尝试，域名失效可替换） */
    val MIRROR_PREFIXES: List<String> = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
    )

    /**
     * 候选 URL 重试：依次尝试原地址 + 镜像，全部失败抛出最后一个错误。
     * GitHub 域名自动附加镜像；非 GitHub 直连仅尝试原地址。
     */
    suspend fun downloadFirstAvailable(
        httpClient: OkHttpClient,
        url: String,
        timeoutMs: Long = 30_000,
        validator: (ByteArray) -> Boolean = { true },
    ): ByteArray = withContext(Dispatchers.IO) {
        val candidates = candidates(url)
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                val bytes = httpClient.newCall(
                    Request.Builder().url(candidate).build()
                ).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.bytes() ?: error("空响应")
                }
                if (!validator(bytes)) {
                    lastError = IllegalArgumentException("内容校验失败（已尝试镜像源）")
                    continue
                }
                return@withContext bytes
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("下载失败: $url")
    }

    /** 构造候选地址列表：GitHub 资源附加速镜像，其余仅原地址 */
    private fun candidates(url: String): List<String> {
        if (!isGitHubUrl(url)) return listOf(url)
        return buildList {
            add(url)
            MIRROR_PREFIXES.forEach { mirror -> add(mirror + url) }
        }
    }

    /** 是否为 GitHub 域名（github.com / githubusercontent.com），决定是否走镜像 */
    private fun isGitHubUrl(url: String): Boolean {
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return false
        return host.endsWith("github.com") || host.endsWith("githubusercontent.com")
    }

    /** 判断字节是否为 zip 包（PK 文件头） */
    fun isZipArchive(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** 判断字节是否为 gzip 包（0x1f8b 魔数） */
    fun isGzipArchive(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** owner/repo 拆分；repo 可为 "owner/repo" 或完整 GitHub URL，失败返回 null */
    fun splitRepo(input: String): Pair<String, String>? {
        val text = input.trim().trimEnd('/')
        if (text.isBlank()) return null
        val base = if (text.startsWith("http://") || text.startsWith("https://")) {
            val host = runCatching { java.net.URL(text).host }.getOrNull() ?: return null
            if (host != "github.com") return null
            text.substringAfter("github.com/").substringBefore("/tree/").trim('/').removeSuffix(".git")
        } else {
            text.removeSuffix(".git")
        }
        val parts = base.split('/').filter { it.isNotBlank() }
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return parts[0] to parts[1]
    }
}
