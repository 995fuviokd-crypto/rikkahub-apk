package me.rerere.rikkahub.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIPluginAdapterTest {

    private fun adapter() = OpenAIPluginAdapter(httpClient = okhttp3.OkHttpClient())

    @Test
    fun `build candidate urls for github repo main and master`() {
        val urls = adapter().buildCandidateUrls("github.com/owner/repo")
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/owner/repo/main/.well-known/ai-plugin.json",
                "https://raw.githubusercontent.com/owner/repo/master/.well-known/ai-plugin.json",
            ),
            urls.take(2),
        )
        assertTrue(urls.size >= 2)
    }

    @Test
    fun `build candidate urls for plain domain`() {
        val urls = adapter().buildCandidateUrls("https://example.com/plugin")
        assertEquals(
            listOf("https://example.com/.well-known/ai-plugin.json"),
            urls,
        )
    }

    @Test
    fun `build candidate urls for bare owner repo`() {
        val urls = adapter().buildCandidateUrls("owner/repo")
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/owner/repo/main/.well-known/ai-plugin.json",
                "https://raw.githubusercontent.com/owner/repo/master/.well-known/ai-plugin.json",
            ),
            urls,
        )
    }

    @Test
    fun `manifest maps to plugin info with sanitized id`() {
        val info = adapter().manifestToInfo(
            OpenAIPluginManifest(
                name_for_human = "天气助手",
                name_for_model = "Weather Assistant!",
                description_for_human = "查询天气",
                description_for_model = "You can query weather.",
                contact_email = "a@example.com",
            ),
            sourceUrl = "https://example.com",
        )
        assertEquals("weather-assistant", info.id)
        assertEquals("天气助手", info.name)
        assertEquals("查询天气", info.description)
        assertEquals("You can query weather.", info.systemPrompt)
        assertEquals("a@example.com", info.author)
        assertEquals("plugin", info.type)
        assertTrue(info.tags.contains("openai"))
        assertEquals("https://example.com", info.repository)
    }

    @Test
    fun `empty model name falls back to hash id`() {
        val info = adapter().manifestToInfo(
            OpenAIPluginManifest(name_for_model = "   ", name_for_human = "X"),
        )
        assertTrue(info.id.startsWith("openai-"))
        assertEquals("X", info.name)
    }
}
