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

    @Test
    fun `plugin json parses new fields type tags and extension points`() {
        val info = PluginJson.fromJson(
            """
            {
              "id": "ext",
              "name": "Ext",
              "version": "1.0.0",
              "type": "skill",
              "tags": ["翻译", "写作"],
              "extensionPoints": {
                "settingsActions": [
                  {"id": "s1", "label": "帮助", "target": "url", "payload": "https://example.com"}
                ],
                "homeActions": [
                  {"id": "h1", "label": "快捷", "target": "prompt", "payload": "你好"}
                ]
              }
            }
            """.trimIndent()
        )
        assertEquals("skill", info.type)
        assertEquals(listOf("翻译", "写作"), info.tags)
        assertEquals(1, info.extensionPoints.settingsActions.size)
        assertEquals("https://example.com", info.extensionPoints.settingsActions[0].payload)
        assertEquals("prompt", info.extensionPoints.homeActions[0].target)
    }

    @Test
    fun `plugin json round trip with new fields`() {
        val info = PluginInfo(
            id = "rt2",
            name = "Round Trip 2",
            version = "1.0.0",
            type = "mcp",
            tags = listOf("mcp", "server"),
            extensionPoints = PluginExtensionPoints(
                settingsActions = listOf(PluginExtensionAction("a1", "复制", target = "copy", payload = "x")),
            ),
        )
        val decoded = PluginJson.fromJson(PluginJson.toJson(info))
        assertEquals(info, decoded)
    }

    @Test
    fun `plugin categories type label maps types`() {
        assertEquals("插件", PluginCategories.typeLabel("plugin"))
        assertEquals("技能", PluginCategories.typeLabel("skill"))
        assertEquals("MCP", PluginCategories.typeLabel("mcp"))
        assertEquals("JSON 配置", PluginCategories.typeLabel("json"))
        assertEquals("自定义", PluginCategories.typeLabel("自定义"))
        assertEquals("其他", PluginCategories.typeLabel(""))
    }

    @Test
    fun `plugin json round trips extension points per scope`() {
        val info = PluginManager.extractPluginInfo(
            zipWith(
                "plugin.json" to PluginJson.toJson(
                    PluginInfo(
                        id = "scope-plugin",
                        name = "Scope",
                        version = "1.0.0",
                        extensionPoints = PluginExtensionPoints(
                            settingsActions = listOf(PluginExtensionAction("s1", "设置动作")),
                            homeActions = listOf(PluginExtensionAction("h1", "首页动作")),
                        ),
                    )
                ).toByteArray(),
            )
        ).getOrThrow()
        assertEquals("设置动作", info.extensionPoints.settingsActions[0].label)
        assertEquals("首页动作", info.extensionPoints.homeActions[0].label)
    }
}
