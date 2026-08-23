package me.rerere.rikkahub.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * DSH（DeepSeek Harness）插件市场数据源。
 * 数据来自社区精选注册表 awesome-dsh-plugin 的实时 JSON feed（GitHub Pages，随仓库自动重建）：
 * feed 不可达时降级解析仓库 README.md 条目并合并 data/stars.json 的 star 数据。
 * 安装走 [DshPluginAdapter]（GitHub 仓库 → RikkaHub 插件 zip）。
 */
@Serializable
data class DshMarketPlugin(
    val name: String = "",
    val owner: String = "",
    val url: String = "",
    val category: String = "",
    @SerialName("description") val description: DshDescription = DshDescription(),
    val npm: String? = null,
    val stars: Int = 0,
    val install: String = "",
) {
    /** 仓库引用 owner/repo，供 DshPluginAdapter 拉取转换 */
    val repoRef: String get() = if (owner.isBlank()) name else "$owner/$name"

    val displayDescription: String
        get() = description.zh.ifBlank { description.en }
}

@Serializable
data class DshDescription(
    val zh: String = "",
    val en: String = "",
)

@Serializable
data class DshCategory(
    val id: String,
    val zh: String,
    val en: String,
)

data class DshMarketList(
    val plugins: List<DshMarketPlugin>,
    val categories: List<DshCategory>,
    val updated: String,
    val fromFallback: Boolean,
)

class DshMarketDataSource(
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val FEED_URL = "https://beancookie.github.io/awesome-dsh-plugin/plugins.json"
        private const val README_URL =
            "https://raw.githubusercontent.com/beancookie/awesome-dsh-plugin/main/README.md"
        private const val STARS_URL =
            "https://raw.githubusercontent.com/beancookie/awesome-dsh-plugin/main/data/stars.json"
        private const val MIRROR_PREFIX_GHPROXY = "https://gh-proxy.com/"

        private val json = Json { ignoreUnknownKeys = true }

        /** feed 内置分类（id 与 awesome 仓库一致），README 降级解析也复用 */
        internal val FALLBACK_CATEGORIES = listOf(
            DshCategory("ui", "UI 增强", "UI Enhancements"),
            DshCategory("theme", "主题与外观", "Themes & Appearance"),
            DshCategory("session", "会话与消息", "Sessions & Messages"),
            DshCategory("memory", "记忆", "Memory"),
            DshCategory("tools", "工具与能力", "Tools & Capabilities"),
            DshCategory("skill", "技能包", "Skills"),
            DshCategory("workflow", "工作流与自动化", "Workflow & Automation"),
            DshCategory("notify", "通知与集成", "Notifications & Integrations"),
            DshCategory("model", "模型与账号接入", "Models & Providers"),
            DshCategory("dev", "开发与运行时", "Development & Runtime"),
            DshCategory("fun", "娱乐", "Just for Fun"),
        )

        fun categoryLabel(id: String): String =
            FALLBACK_CATEGORIES.firstOrNull { it.id == id }?.zh ?: id

        /** README 分类标题（含 emoji）→ category id */
        private fun categoryFromHeading(heading: String): String? {
            val normalized = heading.trim().trimStart('#').trim()
            return FALLBACK_CATEGORIES.firstOrNull { c ->
                normalized.contains(c.zh) || normalized.contains(c.en, ignoreCase = true)
            }?.id
        }
    }

    /**
     * 拉取 DSH 市场列表。
     * 优先实时 feed；失败降级 raw README.md（走镜像）+ stars.json 合并。
     */
    suspend fun fetchList(): Result<DshMarketList> = withContext(Dispatchers.IO) {
        fetchFeed().recoverCatching { fetchReadmeFallback() }
    }

    private fun fetchFeed(): Result<DshMarketList> = runCatching {
        var lastError: Throwable? = null
        for (url in candidates(FEED_URL)) {
            try {
                val feed = json.decodeFromString(FeedDto.serializer(), httpGet(url))
                val plugins = feed.plugins.map { p ->
                    DshMarketPlugin(
                        name = p.name,
                        owner = p.owner,
                        url = p.url,
                        category = p.category,
                        description = p.description,
                        npm = p.npm,
                        stars = p.stars,
                        install = p.install,
                    )
                }
                if (plugins.isEmpty()) error("feed 为空")
                val categories = feed.categories.map { (id, label) ->
                    DshCategory(id, label.zh, label.en)
                }
                return@runCatching DshMarketList(
                    plugins = plugins,
                    categories = categories.ifEmpty { FALLBACK_CATEGORIES },
                    updated = feed.updated,
                    fromFallback = false,
                )
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("DSH feed 拉取失败")
    }

    /** 降级：解析 README 条目行 `- [owner/repo](url) — 描述`，按分类标题分节，合并 stars.json */
    private fun fetchReadmeFallback(): DshMarketList {
        val readme = candidates(README_URL).firstNotNullOfOrNull { url ->
            runCatching { httpGet(url) }.getOrNull()
        } ?: error("DSH 市场 README 拉取失败")
        val stars = candidates(STARS_URL).firstNotNullOfOrNull { url ->
            runCatching { httpGet(url) }.getOrNull()
        }?.let(::parseStarsJson) ?: emptyMap()
        val plugins = parseReadme(readme, stars)
        if (plugins.isEmpty()) error("README 解析不到任何插件条目")
        return DshMarketList(
            plugins = plugins.distinctBy { it.url },
            categories = FALLBACK_CATEGORIES,
            updated = "",
            fromFallback = true,
        )
    }

    /** 解析 stars.json：{ "https://github.com/owner/repo": {"stars": N} } */
    internal fun parseStarsJson(text: String): Map<String, Int> {
        return runCatching {
            val serializer = kotlinx.serialization.builtins.MapSerializer(
                String.serializer(),
                StarEntryDto.serializer(),
            )
            json.decodeFromString(serializer, text).entries
                .associate { (url, info) -> url to info.stars }
        }.getOrDefault(emptyMap())
    }

    /** 解析 README 条目行与分类标题，生成带分类与 star 数据的插件列表 */
    internal fun parseReadme(readme: String, stars: Map<String, Int>): List<DshMarketPlugin> {
        val plugins = mutableListOf<DshMarketPlugin>()
        var currentCategory = ""
        val entryRegex = Regex("""^-\s*\[([\w.-]+/[\w.-]+)]\((https://github\.com/[^)]+)\)\s*[—–-]+\s*(.+)$""")
        val headingRegex = Regex("""^###\s+(.+)$""")
        readme.lines().forEach { line ->
            headingRegex.find(line.trim())?.let { m ->
                currentCategory = categoryFromHeading(m.groupValues[1]).orEmpty()
                return@forEach
            }
            entryRegex.find(line.trim())?.let { m ->
                plugins += DshMarketPlugin(
                    name = m.groupValues[1].substringAfter('/'),
                    owner = m.groupValues[1].substringBefore('/'),
                    url = m.groupValues[2],
                    category = currentCategory,
                    description = DshDescription(zh = m.groupValues[3].trim()),
                    stars = stars[m.groupValues[2]] ?: 0,
                )
            }
        }
        return plugins
    }

    private data class Candidate(val url: String)

    private fun candidates(url: String): List<String> = listOf(
        url,
        MIRROR_PREFIX_GHPROXY + url,
        "https://cdn.jsdelivr.net/" + url.removePrefix("https://raw.githubusercontent.com/"),
    )

    private fun httpGet(url: String): String {
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string() ?: error("空响应")
        }
    }

    @Serializable
    private data class FeedDto(
        val count: Int = 0,
        val updated: String = "",
        val categories: Map<String, FeedCategoryDto> = emptyMap(),
        val plugins: List<FeedPluginDto> = emptyList(),
    )

    @Serializable
    private data class FeedCategoryDto(
        val zh: String = "",
        val en: String = "",
    )

    @Serializable
    private data class FeedPluginDto(
        val name: String = "",
        val owner: String = "",
        val url: String = "",
        val category: String = "",
        val description: DshDescription = DshDescription(),
        val npm: String? = null,
        val stars: Int = 0,
        val install: String = "",
    )

    @Serializable
    private data class StarEntryDto(
        val stars: Int = 0,
    )
}
