package me.rerere.rikkahub.data.plugin

import me.rerere.rikkahub.data.ai.mcp.serverUrl
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

    @Test
    fun `autoAdapt generates plugin json for skill markdown`() {
        val dir = kotlin.io.path.createTempDirectory("adapt-skill").toFile()
        try {
            File(dir, "SKILL.md").writeText(
                "---\nname: 经验笔记\ndescription: 记录排错经验\n---\n\n# 技能正文\n记录并整理经验。"
            )
            val info = PluginManager.autoAdapt(dir)
            assertNotNull(info)
            info!!
            assertEquals("skill", info.type)
            assertEquals("经验笔记", info.name)
            assertTrue(info.systemPrompt.contains("记录并整理经验"))
            assertTrue(info.id.startsWith("resource-"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `autoAdapt generates plugin json for character card v3`() {
        val dir = kotlin.io.path.createTempDirectory("adapt-chara").toFile()
        try {
            File(dir, "character.json").writeText(
                """
                {
                  "spec": "chara_card_v3",
                  "name": "小猫娘",
                  "description": "可爱的小猫娘",
                  "data": {
                    "name": "小猫娘",
                    "system_prompt": "你是小猫娘喵，用可爱的语气回复。",
                    "personality": "粘人、活泼",
                    "first_mes": "主人喵~今天也要陪我玩吗？"
                  }
                }
                """.trimIndent()
            )
            val info = PluginManager.autoAdapt(dir)
            assertNotNull(info)
            info!!
            assertEquals("character", info.type)
            assertEquals("小猫娘", info.name)
            assertTrue(info.systemPrompt.contains("用可爱的语气回复"))
            assertTrue(info.systemPrompt.contains("开场白"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `autoAdapt generates plugin json for mcp json`() {
        val dir = kotlin.io.path.createTempDirectory("adapt-mcp").toFile()
        try {
            File(dir, "mcp.json").writeText(
                """
                {
                  "mcpServers": {
                    "天气": {
                      "url": "https://weather.example.com/mcp",
                      "type": "streamable_http"
                    }
                  }
                }
                """.trimIndent()
            )
            val info = PluginManager.autoAdapt(dir)
            assertNotNull(info)
            info!!
            assertEquals("mcp", info.type)
            assertEquals("MCP: 天气", info.name)
            val servers = PluginManager.parseMcpServers(File(dir, "mcp.json"))
            assertEquals(1, servers.size)
            assertEquals("https://weather.example.com/mcp", servers[0].serverUrl)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseMcpServers parses command stdio npx configs`() {
        val dir = kotlin.io.path.createTempDirectory("adapt-mcp-command").toFile()
        try {
            File(dir, "mcp.json").writeText(
                """
                {
                  "mcpServers": {
                    "filesystem": {
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
                      "env": { "DISABLE_AUTO_UPDATE": "true" },
                      "type": "stdio"
                    },
                    "remote": {
                      "url": "https://remote.example.com/sse",
                      "type": "sse"
                    }
                  }
                }
                """.trimIndent()
            )
            val servers = PluginManager.parseMcpServers(File(dir, "mcp.json"))
            assertEquals(2, servers.size)
            val command = servers.first { it is me.rerere.rikkahub.data.ai.mcp.McpServerConfig.CommandServerConfig }
            assertTrue(command is me.rerere.rikkahub.data.ai.mcp.McpServerConfig.CommandServerConfig)
            val cmd = command as me.rerere.rikkahub.data.ai.mcp.McpServerConfig.CommandServerConfig
            assertEquals("npx", cmd.command)
            assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "/tmp"), cmd.args)
            assertEquals("true", cmd.env["DISABLE_AUTO_UPDATE"])
            assertTrue(cmd.serverUrl.startsWith("local:"))
            assertEquals("filesystem", cmd.commonOptions.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `autoAdapt returns null for unrecognized bundle`() {
        val dir = kotlin.io.path.createTempDirectory("adapt-none").toFile()
        try {
            File(dir, "data.txt").writeText("hello")
            assertEquals(null, PluginManager.autoAdapt(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 第三方 schema 归一化（Operit 原生格式等） ----

    /** 市场真实样例：operit-agent-diary 的 Operit 原生 plugin.json */
    private fun operitNativeJson(): String =
        """
        {
          "name": "Agent日记本",
          "package": "operit-agent-diary",
          "version": "1.0.0",
          "description": "让 AI Agent 写日记和感想的插件，日记存储在本地，读取权限由 AI 审批管控，附侧边栏可视化界面。",
          "author": "MonkeyCode",
          "icon": "📔",
          "permissions": [],
          "web_path": "web/index.html",
          "sidebar": true
        }
        """.trimIndent()

    private fun toolManifestJson(): String =
        """
        {
          "name": "Agent日记本",
          "description": "让 AI Agent 写日记和感想的插件，带侧边栏可视化界面。",
          "tools": [
            {"name": "write_diary", "description": "写一篇新日记"},
            {"name": "list_diaries", "description": "列出所有日记的摘要"}
          ]
        }
        """.trimIndent()

    @Test
    fun `normalize maps operit native schema with sidebar entry and tool prompt`() {
        val dir = kotlin.io.path.createTempDirectory("norm-operit").toFile()
        try {
            File(dir, "plugin.json").writeText(operitNativeJson())
            File(dir, "operit/toolmanifest.json").apply { parentFile.mkdirs() }.writeText(toolManifestJson())
            val normalized = PluginManager.normalizePluginJson(operitNativeJson(), dir)
            assertNotNull(normalized)
            val info = PluginJson.fromJson(normalized!!)
            assertEquals("operit-agent-diary", info.id)
            assertEquals("Agent日记本", info.name)
            assertEquals("plugin", info.type)
            // 侧边栏入口：web_path 转 webview payload
            assertEquals(1, info.extensionPoints.sidebarActions.size)
            val action = info.extensionPoints.sidebarActions[0]
            assertEquals("webview", action.target)
            assertEquals("plugin://operit-agent-diary/index.html", action.payload)
            // 能力提示词从 toolmanifest 生成，指向真实的 run_script_tool
            assertTrue(info.systemPrompt.contains("run_script_tool"))
            assertTrue(info.systemPrompt.contains("write_diary"))
            assertTrue(info.systemPrompt.contains("list_diaries"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `normalize passes standard json through unchanged`() {
        assertEquals(null, PluginManager.normalizePluginJson(validPluginJson(), null))
    }

    @Test
    fun `normalize fixes legacy operit tool reference in standard packages`() {
val legacy = validPluginJson().replace(
            "\"You are helpful with demos.\"",
            "\"可用 `run_operit_tool` 工具调用能力\""
        )
        val fixed = PluginManager.normalizePluginJson(legacy, null)
        assertNotNull(fixed)
        assertTrue(fixed!!.contains("run_script_tool"))
        assertEquals(false, fixed.contains("run_operit_tool"))
    }

    @Test
    fun `normalize infers skill type and prompt from SKILL md`() {
        val dir = kotlin.io.path.createTempDirectory("norm-skill").toFile()
        try {
            File(dir, "SKILL.md").writeText("# 技能正文\n整理经验的方法论。")
            val raw = """{"name":"经验笔记","version":"1.0.0"}"""
            val normalized = PluginManager.normalizePluginJson(raw, dir)
            assertNotNull(normalized)
            val info = PluginJson.fromJson(normalized!!)
            assertEquals("skill", info.type)
            assertTrue(info.systemPrompt.contains("技能正文"))
            assertTrue(info.id.startsWith("resource-") || info.id.isNotBlank())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `extractPluginInfo tolerates operit schema zip`() {
        val zip = zipWith(
            "plugin.json" to operitNativeJson().toByteArray(),
            "operit/main.js" to "module.exports = {}".toByteArray(),
        )
        val result = PluginManager.extractPluginInfo(zip)
        assertTrue(result.isSuccess)
        assertEquals("operit-agent-diary", result.getOrThrow().id)
    }

    @Test
    fun `extractPluginInfo still fails on malformed json`() {
        val zip = zipWith("plugin.json" to "{not json".toByteArray())
        assertTrue(PluginManager.extractPluginInfo(zip).isFailure)
    }

    @Test
    fun `autoAdapt generates script plugin from operit directory`() {
        val dir = kotlin.io.path.createTempDirectory("adapt-script").toFile()
        try {
            File(dir, "operit/toolmanifest.json").apply { parentFile.mkdirs() }.writeText(toolManifestJson())
            File(dir, "operit/main.js").writeText("module.exports = {}")
            val info = PluginManager.autoAdapt(dir)
            assertNotNull(info)
            info!!
            assertEquals("plugin", info.type)
            assertEquals("Agent日记本", info.name)
            assertTrue(info.systemPrompt.contains("write_diary"))
            assertTrue(info.systemPrompt.contains("run_script_tool"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
