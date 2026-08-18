package me.rerere.rikkahub.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginManagerTest {

    private fun zipWith(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun validPluginJson(): String =
        """
        {
          "id": "demo-plugin",
          "name": "Demo Plugin",
          "version": "1.2.0",
          "description": "A demo",
          "author": "tester",
          "category": "development",
          "systemPrompt": "You are helpful with demos.",
          "actions": [
            {"label": "Translate", "prompt": "Please translate the following:"}
          ]
        }
        """.trimIndent()

    @Test
    fun `parseArchive extracts plugin json from zip`() {
        val zip = zipWith(
            "plugin.json" to validPluginJson().toByteArray(),
            "README.md" to "readme".toByteArray(),
            "assets/data.txt" to "data".toByteArray(),
        )
        val result = PluginManager.extractPluginInfo(zip)
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals("demo-plugin", info.id)
        assertEquals("Demo Plugin", info.name)
        assertEquals("1.2.0", info.version)
        assertEquals("development", info.category)
        assertEquals(1, info.actions.size)
        assertEquals("Translate", info.actions[0].label)
    }

    @Test
    fun `parseArchive uses root plugin json`() {
        val zip = zipWith(
            "sub/plugin.json" to "{\"id\":\"nested\"}".toByteArray(),
            "plugin.json" to validPluginJson().toByteArray(),
        )
        val info = PluginManager.extractPluginInfo(zip).getOrThrow()
        assertEquals("demo-plugin", info.id)
    }

    @Test
    fun `parseArchive fails when plugin json missing`() {
        val zip = zipWith(
            "SKILL.md" to "# skill".toByteArray(),
        )
        val result = PluginManager.extractPluginInfo(zip)
        assertTrue(result.isFailure)
    }

    @Test
    fun `parseArchive fails on malformed plugin json`() {
        val zip = zipWith(
            "plugin.json" to "{not json".toByteArray(),
        )
        val result = PluginManager.extractPluginInfo(zip)
        assertTrue(result.isFailure)
    }

    @Test
    fun `unzip flattens path traversal and dots`() {
        val target = kotlin.io.path.createTempDirectory("plugin-test").toFile()
        try {
            PluginManager.unzipTo(
                zipWith(
                    "../evil.txt" to "x".toByteArray(),
                    "./ok.txt" to "ok".toByteArray(),
                    "dir/file.txt" to "y".toByteArray(),
                ),
                target,
            )
            assertTrue("path traversal must not escape", File(target, "evil.txt").parentFile.absolutePath.startsWith(target.absolutePath))
            assertTrue(File(target, "ok.txt").exists())
            assertTrue(File(target, "dir/file.txt").exists())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `plugin json parsing fills defaults`() {
        val info = PluginJson.fromJson(
            """{"id":"x","name":"X","version":"0.1.0"}"""
        )
        assertEquals("x", info.id)
        assertEquals("X", info.name)
        assertEquals("general", info.category)
        assertEquals("", info.systemPrompt)
        assertTrue(info.actions.isEmpty())
    }

    @Test
    fun `plugin json ignores unknown fields`() {
        val info = PluginJson.fromJson(
            """{"id":"x","name":"X","version":"0.1.0","extra":{"a":1},"unknown":"y"}"""
        )
        assertEquals("x", info.id)
    }

    @Test
    fun `plugin json round trip`() {
        val info = PluginInfo(
            id = "rt",
            name = "Round Trip",
            version = "1.0.0",
            category = "media",
            actions = listOf(PluginAction("Run", "do it")),
        )
        val decoded = PluginJson.fromJson(PluginJson.toJson(info))
        assertEquals(info, decoded)
    }

    @Test
    fun `archive name uses id and version`() {
        val info = PluginInfo(id = "demo", name = "D", version = "2.3.4")
        val archive = PluginArchive(info = info, fileName = "demo.zip", content = ByteArray(0))
        assertEquals("demo-2.3.4.zip", archive.zipFileName)
        assertNotNull(archive.content)
    }
}
