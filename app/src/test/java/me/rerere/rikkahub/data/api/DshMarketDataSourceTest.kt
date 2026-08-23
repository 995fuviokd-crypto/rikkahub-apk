package me.rerere.rikkahub.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshMarketDataSourceTest {

    private val dataSource = DshMarketDataSource(okhttp3.OkHttpClient())

    // ---- parseReadme：条目行与分类标题 ----

    @Test
    fun `parseReadme extracts entries with categories and stars`() {
        val readme = """
            # Awesome DSH Plugin
            ## 插件

            ### 🎨 UI 增强
            - [0xsline/dsh-spotlight](https://github.com/0xsline/dsh-spotlight) — 键盘优先的命令面板。
            - [a903067276-rgb/dsh-file-mentions](https://github.com/a903067276-rgb/dsh-file-mentions) — 文件路径可点击。

            ### 🛠️ 工具与能力
            - [liustack/modlens](https://github.com/liustack/modlens) — 视觉插件。
        """.trimIndent()
        val stars = mapOf("https://github.com/liustack/modlens" to 857)

        val plugins = dataSource.parseReadme(readme, stars)

        assertEquals(3, plugins.size)
        val spotlight = plugins.first { it.name == "dsh-spotlight" }
        assertEquals("0xsline", spotlight.owner)
        assertEquals("ui", spotlight.category)
        assertEquals(0, spotlight.stars)
        assertTrue(spotlight.displayDescription.contains("命令面板"))
        val modlens = plugins.first { it.name == "modlens" }
        assertEquals("tools", modlens.category)
        assertEquals(857, modlens.stars)
        assertEquals("liustack/modlens", modlens.repoRef)
    }

    @Test
    fun `parseReadme returns empty for content without entries`() {
        val plugins = dataSource.parseReadme("# 标题\n没有条目内容", emptyMap())
        assertTrue(plugins.isEmpty())
    }

    // ---- parseStarsJson ----

    @Test
    fun `parseStarsJson maps repo url to star count`() {
        val text = """
            {
              "https://github.com/a/b": {"stars": 12, "createdAt": "2026-08-13"},
              "https://github.com/c/d": {"stars": 0}
            }
        """.trimIndent()

        val stars = dataSource.parseStarsJson(text)

        assertEquals(2, stars.size)
        assertEquals(12, stars["https://github.com/a/b"])
    }

    @Test
    fun `parseStarsJson tolerates malformed input`() {
        assertTrue(dataSource.parseStarsJson("not json").isEmpty())
    }

    // ---- 分类标签 ----

    @Test
    fun `categoryLabel resolves known ids`() {
        assertEquals("UI 增强", DshMarketDataSource.categoryLabel("ui"))
        assertEquals("工具与能力", DshMarketDataSource.categoryLabel("tools"))
        assertEquals("unknown-cat", DshMarketDataSource.categoryLabel("unknown-cat"))
    }
}
