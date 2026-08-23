package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.local.applySetting
import me.rerere.rikkahub.data.ai.tools.local.settingsSnapshot
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemControlToolsTest {
    @Test
    fun `snapshot should expose all whitelisted bool settings`() {
        val snapshot = settingsSnapshot(Settings())

        assertTrue(snapshot.containsKey("developerMode"))
        assertTrue(snapshot.containsKey("dynamicColor"))
        assertTrue(snapshot.containsKey("autoApproveTools"))
        assertTrue(snapshot.containsKey("autonomousExecutionEnabled"))
        assertTrue(snapshot.containsKey("keepAliveEnabled"))
        assertTrue(snapshot.containsKey("floatingBubbleEnabled"))
        assertTrue(snapshot.containsKey("ocrEnabled"))
        assertTrue(snapshot.containsKey("webServerEnabled"))
        assertTrue(snapshot.containsKey("enableSuggestion"))
        assertTrue(snapshot.containsKey("multiRouteConcurrent"))
        assertTrue(snapshot.containsKey("recallSegmented"))
        assertTrue(snapshot.containsKey("recallBoundaryPunctuation"))
        assertTrue(snapshot.containsKey("recallRollbackEnabled"))
        assertTrue(snapshot.containsKey("recallInformedAi"))
        assertTrue(snapshot.containsKey("memoryJournalEnabled"))
    }

    @Test
    fun `snapshot should reflect field values`() {
        val snapshot = settingsSnapshot(
            Settings().copy(
                developerMode = true,
                dynamicColor = false,
                webServerPort = 9090,
                enabledPlugins = setOf("a", "b"),
            )
        )

        assertEquals("true", snapshot["developerMode"]?.jsonPrimitive?.content)
        assertEquals("false", snapshot["dynamicColor"]?.jsonPrimitive?.content)
        assertEquals("9090", snapshot["webServerPort"]?.jsonPrimitive?.content)
        assertEquals("a,b", snapshot["enabledPlugins"]?.jsonPrimitive?.content)
    }

    @Test
    fun `applySetting should update booleans`() {
        val base = Settings().copy(developerMode = false)
        val updated = applySetting(base, "developerMode", "true")

        assertTrue(updated.developerMode)
        assertFalse(base.developerMode)
    }

    @Test
    fun `applySetting should update ints and floats`() {
        val base = Settings().copy(webServerPort = 8080)
        val updated = applySetting(base, "webServerPort", "9090")

        assertEquals(9090, updated.webServerPort)

        val withScore = applySetting(base, "memoryMinScore", "0.9")
        assertEquals(0.9f, withScore.memoryMinScore)
    }

    @Test
    fun `applySetting should update prompts`() {
        val base = Settings().copy(globalPrompt = "old")
        val updated = applySetting(base, "globalPrompt", "new prompt")

        assertEquals("new prompt", updated.globalPrompt)
    }

    @Test
    fun `applySetting should throw on invalid bool value`() {
        val base = Settings()
        assertThrows(IllegalArgumentException::class.java) {
            applySetting(base, "developerMode", "maybe")
        }
    }

    @Test
    fun `applySetting should throw on unknown key`() {
        val base = Settings()
        assertThrows(IllegalArgumentException::class.java) {
            applySetting(base, "notARealKey", "1")
        }
    }
}