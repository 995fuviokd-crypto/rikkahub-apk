package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEndpointResolverTest {

    @Test
    fun `root domain appends v1`() {
        assertEquals("https://tabitoken.com/v1", ApiEndpointResolver.resolveBaseUrl("https://tabitoken.com/"))
        assertEquals("https://tabitoken.com/v1", ApiEndpointResolver.resolveBaseUrl("https://tabitoken.com"))
    }

    @Test
    fun `existing path keeps unchanged`() {
        assertEquals(
            "https://api.openai.com/v1",
            ApiEndpointResolver.resolveBaseUrl("https://api.openai.com/v1")
        )
        assertEquals(
            "https://example.com/custom",
            ApiEndpointResolver.resolveBaseUrl("https://example.com/custom")
        )
    }

    @Test
    fun `trailing slash is trimmed`() {
        assertEquals(
            "https://api.openai.com/v1",
            ApiEndpointResolver.resolveBaseUrl("https://api.openai.com/v1/")
        )
    }

    @Test
    fun `explicit mode strips at sign and disables v1 append`() {
        assertEquals(
            "https://tabitoken.com",
            ApiEndpointResolver.resolveBaseUrl("https://tabitoken.com/@")
        )
        assertTrue(ApiEndpointResolver.isExplicitBaseUrl("https://tabitoken.com/@"))
        assertFalse(ApiEndpointResolver.isExplicitBaseUrl("https://tabitoken.com/"))
    }

    @Test
    fun `chat endpoint appends path to resolved base`() {
        assertEquals(
            "https://tabitoken.com/v1/chat/completions",
            ApiEndpointResolver.resolveEndpoint("https://tabitoken.com/", "/chat/completions")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ApiEndpointResolver.resolveEndpoint("https://api.openai.com/v1", "/chat/completions")
        )
    }

    @Test
    fun `embeddings endpoint adapts automatically`() {
        assertEquals(
            "https://tabitoken.com/v1/embeddings",
            ApiEndpointResolver.resolveEndpoint("https://tabitoken.com/", "/embeddings")
        )
    }

    @Test
    fun `models endpoint adapts automatically`() {
        assertEquals(
            "https://tabitoken.com/v1/models",
            ApiEndpointResolver.resolveEndpoint("https://tabitoken.com/", "/models")
        )
    }

    @Test
    fun `explicit mode returns base directly without path append`() {
        assertEquals(
            "https://tabitoken.com/chat/completions",
            ApiEndpointResolver.resolveEndpoint("https://tabitoken.com/chat/completions@", "/models")
        )
        assertEquals(
            "https://tabitoken.com/v1",
            ApiEndpointResolver.resolveEndpoint("https://tabitoken.com/v1@", "/models")
        )
    }

    @Test
    fun `blank input stays blank`() {
        assertEquals("", ApiEndpointResolver.resolveBaseUrl(""))
        assertEquals("", ApiEndpointResolver.resolveBaseUrl("   "))
    }
}
