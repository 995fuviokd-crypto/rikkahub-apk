package me.rerere.rikkahub.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient

/**
 * DSH（DeepSeek Harness）插件市场数据源。
 * 数据来自社区精选注册表 awesome-dsh-plugin 的实时 JSON feed
 * （官方站点 https://awesome-dsh-plugin.com/plugins.json，随仓库自动重建）：
 * feed 不可达时降级解析仓库 README.md 条目并合并 data/stars.json 的 star 数据。
 * 安装优先走 feed 的 tarball/npm 直链，其次经 [DshPluginAdapter]（GitHub 仓库 → RikkaHub 插件 zip）。
 */
@Serializable
data class DshMarketPlugin(
    val name: String = "",
    val owner: String = "",
    val url: String = "",
    val category: String = "",
    @SerialName("description") val description: DshDescription = DshDescription(),
    val npm: String? = null,
    /** 官方 feed 提供的 npm tarball 直链（发布 Release 资产时存在），安装时优先使用 */
    val tarball: String? = null,
    /** 官方站点详情页（含截图/版本历史） */
    val page: String = "",
    val stars: Int = 0,
    /** 近 30 天下载量 */
    val downloads: Int = 0,
    val install: String = "",
) {
    /** 仓库引用 owner/repo，供 DshPluginAdapter 拉取转换 */
    val repoRef: String get() = if (owner.isBlank()) name else "$owner/$name"

    val displayDescription: String
        get() = description.zh.ifBlank { description.en }

    /** feed 是否提供 tarball 直链（可选安装通道） */
    val hasTarball: Boolean get() = !tarball.isNullOrBlank()
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
        /** 官方实时 feed：awesome-dsh-plugin 组织官网（仓储自动重建，含 tarball/downloads 字段） */
        private const val FEED_URL = "https://awesome-dsh-plugin.com/plugins.json"

        /** 降级：解析官方仓库 README 条目（迁移到新组织后原 beancookie 仓库停更） */
        private const val README_URL =
            "https://raw.githubusercontent.com/awesome-dsh-plugin/awesome-dsh-plugin/main/README.md"
        private const val STARS_URL =
            "https://raw.githubusercontent.com/awesome-dsh-plugin/awesome-dsh-plugin/main/data/stars.json"

        private val json = MarketHttp.json

        /** feed 内置分类（id 与官方站点一致），README 降级解析也复用 */
        internal val FALLBACK_CATEGORIES = listOf(
            DshCategory("agi", "AGI 架构探索", "AGI Architecture Exploration"),
            DshCategory("ui", "UI 增强", "UI Enhancements"),
            DshCategory("usage", "用量与计费", "Usage & Billing"),
            DshCategory("theme", "主题与外观", "Themes & Appearance"),
            DshCategory("model", "模型与账号接入", "Models & Providers"),
            DshCategory("identity", "身份与通信", "Identity & Communication"),
            DshCategory("session", "会话与消息", "Sessions & Messages"),
            DshCategory("memory", "记忆", "Memory"),
            DshCategory("tools", "工具与能力", "Tools & Capabilities"),
            DshCategory("browser", "浏览器与网页", "Browser & Web"),
            DshCategory("vision", "视觉与多模态", "Vision & Multimodal"),
            DshCategory("voice", "语音与音频", "Voice & Audio"),
            DshCategory("docs", "文档与渲染", "Documentation & Rendering"),
            DshCategory("skill", "技能包", "Skills"),
            DshCategory("workflow", "工作流与自动化", "Workflow & Automation"),
            DshCategory("git", "Git 与代码评审", "Git & Code Review"),
            DshCategory("notify", "通知与集成", "Notifications & Integrations"),
            DshCategory("dev", "开发与运行时", "Development & Runtime"),
            DshCategory("security", "安全与权限", "Security & Permissions"),
            DshCategory("remote", "远程与移动端", "Remote & Mobile"),
            DshCategory("market", "插件市场与管理", "Plugin Market & Management"),
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

    private suspend fun fetchFeed(): Result<DshMarketList> = runCatching {
        val feed = json.decodeFromString(FeedDto.serializer(), httpGet(FEED_URL))
        val plugins = feed.plugins.map { p ->
            DshMarketPlugin(
                name = p.name,
                owner = p.owner,
                url = p.url,
                category = p.category,
                description = p.description,
                npm = p.npm,
                tarball = p.tarball,
                page = p.page,
                stars = p.stars,
                downloads = p.downloads,
                install = p.install,
            )
        }
        if (plugins.isEmpty()) error("feed 为空")
        val categories = feed.categories.map { (id, label) ->
            DshCategory(id, label.zh, label.en)
        }
        DshMarketList(
            plugins = plugins,
            categories = categories.ifEmpty { FALLBACK_CATEGORIES },
            updated = feed.updated,
            fromFallback = false,
        )
    }

    /** 降级：解析 README 条目行 `- [owner/repo](url) — 描述`，按分类标题分节，合并 stars.json */
    private suspend fun fetchReadmeFallback(): DshMarketList {
        val readme = httpGet(README_URL)
        val stars = runCatching { httpGet(STARS_URL) }.getOrNull()?.let(::parseStarsJson) ?: emptyMap()
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

    /** 下载文本（GitHub 域名自动附加速镜像），供 feed/README/stars 复用 */
    private suspend fun httpGet(url: String): String {
        val bytes = MarketHttp.downloadFirstAvailable(httpClient, url, timeoutMs = 30_000)
        return bytes.toString(Charsets.UTF_8)
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
        val tarball: String? = null,
        val page: String = "",
        val stars: Int = 0,
        val downloads: Int = 0,
        val install: String = "",
    )

    @Serializable
    private data class StarEntryDto(
        val stars: Int = 0,
    )
}
