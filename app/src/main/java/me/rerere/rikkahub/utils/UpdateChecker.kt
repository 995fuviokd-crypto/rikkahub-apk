package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request

// 更新源：我们的 GitHub Releases，应用内自动检测并下载，无需手动访问 GitHub
private const val API_URL = "https://api.github.com/repos/995fuviokd-crypto/rikkahub-apk/releases/latest"
private const val ASSET_NAME_PREFIX = "RikkaHub-"

// gh-proxy 加速前缀：弱网环境代理 GitHub 下载，提升更新包下载速度
private const val GH_PROXY_PREFIX = "https://gh-proxy.com/"

class UpdateChecker(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        // 记录正在等待自动安装的下载任务 ID, 供 UpdateDownloadReceiver 匹配
        @Volatile
        var pendingInstallDownloadId: Long = -1L
    }

    // 简单的进程内缓存, 避免频繁进入聊天页消耗 GitHub API 匿名额度
    private var cachedResult: UpdateInfo? = null
    private var cachedAt: Long = 0L
    private val cacheTtlMillis = 10 * 60 * 1000L // 10 分钟

    fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(
            UiState.Success(
                data = try {
                    val now = System.currentTimeMillis()
                    val cached = cachedResult
                    if (cached != null && now - cachedAt < cacheTtlMillis) {
                        cached
                    } else {
                        val response = client.newCall(
                            Request.Builder()
                                .url(API_URL)
                                .get()
                                .addHeader(
                                    "User-Agent",
                                    "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                                )
                                .addHeader("Accept", "application/vnd.github+json")
                                .build()
                        ).await()
                        if (response.isSuccessful) {
                            parseGithubRelease(response.body.string()).also {
                                cachedResult = it
                                cachedAt = System.currentTimeMillis()
                            }
                        } else {
                            throw Exception("Failed to fetch update info")
                        }
                    }
                } catch (e: Exception) {
                    throw Exception("Failed to fetch update info", e)
                }
            )
        )
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    /**
     * 将 GitHub Releases 响应解析为 UpdateInfo。
     * GitHub API 响应格式：
     * {
     *   "tag_name": "v2.4.14",
     *   "published_at": "2026-08-16T10:30:00Z",
     *   "body": "...changelog...",
     *   "assets": [{ "name": "RikkaHub-2.4.14-universal-debug.apk", "browser_download_url": "...", "size": 92217887 }]
     * }
     */
    private fun parseGithubRelease(body: String): UpdateInfo {
        val root = json.parseToJsonElement(body).jsonObject
        val version = root["tag_name"]?.jsonPrimitive?.contentOrNull
            ?.removePrefix("v")
            ?: throw Exception("Missing version in update info")
        val publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull ?: ""
        val changelog = root["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val downloads = root["assets"]?.jsonArray?.mapNotNull { asset ->
            val obj = asset.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!name.startsWith(ASSET_NAME_PREFIX)) return@mapNotNull null
            val rawUrl = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = GH_PROXY_PREFIX + rawUrl
            val size = obj["size"]?.jsonPrimitive?.contentOrNull ?: ""
            UpdateDownload(
                name = name,
                url = url,
                size = formatSize(size.toLongOrNull() ?: 0L),
            )
        }.orEmpty()
        if (downloads.isEmpty()) {
            throw Exception("No APK asset found in update")
        }
        return UpdateInfo(
            version = version,
            publishedAt = publishedAt,
            changelog = changelog,
            downloads = downloads,
        )
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                // 设置下载时通知栏的标题和描述
                setTitle(download.name)
                setDescription("正在下载更新包...")
                // 下载完成后通知栏可见
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 允许在移动网络和WiFi下下载
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                // 设置文件保存路径
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                // 允许下载的文件类型
                setMimeType("application/vnd.android.package-archive")
            }
            // 获取系统的DownloadManager
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            // 记录 downloadId, 下载完成后由 UpdateDownloadReceiver 自动弹出安装界面
            pendingInstallDownloadId = downloadId
        }.onFailure {
            Toast.makeText(context, "Failed to update", Toast.LENGTH_SHORT).show()
            context.openUrl(download.url) // 跳转到下载页面
        }
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

/**
 * 是否应向用户展示更新卡片。
 *
 * 满足任一条件即显示更新：
 * 1. 远端版本高于本地版本（常规升序推送，latest > current）；
 * 2. 远端 release 发布时间晚于本地 APK 安装/更新时间（覆盖推送）。
 *
 * 条件 2 用于解决"版本号相同或过低导致无法推送更新"的问题：
 * - 同版本重新构建（如修复 bug 后以相同版本号重发）也能送达已装该版本的用户；
 * - 即使未来发布号低于某些设备已装版本，只要发布时间更新，同样能覆盖推送。
 * 已装版本比远端新且远端发布时间更早（正常最新状态）时不会打扰用户。
 */
fun shouldShowUpdate(
    latestVersion: String,
    currentVersion: String,
    latestPublishedAt: String,
    localInstallTimeMillis: Long,
): Boolean {
    if (Version(latestVersion) > Version(currentVersion)) return true
    val publishedAtMillis = runCatching { java.time.Instant.parse(latestPublishedAt).toEpochMilli() }.getOrNull()
        ?: return false
    return publishedAtMillis > localInstallTimeMillis
}

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
