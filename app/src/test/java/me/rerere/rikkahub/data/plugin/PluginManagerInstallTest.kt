package me.rerere.rikkahub.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest

/**
 * 插件安装链路端到端验证（Robolectric）：
 * 第三方 Operit 原生格式包安装即用；旧版本安装的坏包在列表读取时自愈。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginManagerInstallTest {

    private lateinit var manager: PluginManager
    private lateinit var pluginsDir: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        manager = PluginManager(
            context,
            me.rerere.rikkahub.data.script.ScriptRuntime(context),
        )
        pluginsDir = manager.getPluginsDir()
    }

    /** 市场真实样例：Operit 原生 schema（package 字段当 id、web_path/sidebar 入口、无 type/systemPrompt） */
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

    @Test
    fun `installZip normalizes operit package end to end`() = runTest {
        val bytes = zipWith(
            "plugin.json" to operitNativeJson().toByteArray(),
            "operit/main.js" to "module.exports = {}".toByteArray(),
            "operit/toolmanifest.json" to toolManifestJson().toByteArray(),
            "web/index.html" to "<html>panel</html>".toByteArray(),
        )
        val result = manager.installZip(bytes)
        assertTrue("安装应成功: ${result.exceptionOrNull()}", result.isSuccess)
        val installed = manager.listPlugins().single()
        assertEquals("operit-agent-diary", installed.id)
        assertEquals(PluginStatus.INSTALLED, installed.status)
        val info = installed.info!!
        assertEquals("plugin", info.type)
        assertEquals(1, info.extensionPoints.sidebarActions.size)
        assertEquals(
            "plugin://operit-agent-diary/index.html",
            info.extensionPoints.sidebarActions[0].payload,
        )
        assertTrue(info.systemPrompt.contains("run_script_tool"))
        // 归一化后的 plugin.json 已写回插件目录
        assertTrue(File(pluginsDir, "operit-agent-diary/plugin.json").readText().contains("\"id\""))
    }

    @Test
    fun `listPlugins self heals legacy broken operit install`() = runTest {
        // 模拟旧版本直接落盘的 Operit 原生包（无归一化，解析必然失败）；列表读取时自动归一化修复
        val pluginDir = File(pluginsDir, "operit-murmur-sidebar").apply { mkdirs() }
        File(pluginDir, "plugin.json").writeText(
            """
            {"name":"碎碎念","package":"operit-murmur-sidebar","version":"1.0.0","sidebar":true,"web_path":"web/index.html"}
            """.trimIndent()
        )
        File(pluginDir, "operit/toolmanifest.json").apply { parentFile.mkdirs() }.writeText(toolManifestJson())
        File(pluginDir, "web/index.html").apply { parentFile.mkdirs() }.writeText("<html>murmur</html>")

        val healed = manager.listPlugins()
        val murmur = healed.single()
        assertEquals(PluginStatus.INSTALLED, murmur.status)
        assertEquals("operit-murmur-sidebar", murmur.id)
        assertEquals("碎碎念", murmur.info?.name)
        assertEquals(
            "plugin://operit-murmur-sidebar/index.html",
            murmur.info?.extensionPoints?.sidebarActions?.get(0)?.payload,
        )
    }

    @Test
    fun `installZip still rejects malformed packages`() = runTest {
        val bytes = zipWith("plugin.json" to "{not json".toByteArray())
        assertTrue(manager.installZip(bytes).isFailure)
    }

    @Test
    fun `installZip rejects command only mcp package with clear reason`() = runTest {
        // Claude Code 标准 mcp.json：仅本地 command 服务，Android 端不可运行，应拒绝并说明原因
        val bytes = zipWith(
            "mcp.json" to
                """{"mcpServers":{"github":{"command":"npx","args":["-y","some-server"]}}}"""
                    .toByteArray(),
        )
        val result = manager.installZip(bytes)
        assertTrue(result.isFailure)
        assertTrue(
            "错误信息应解释 command 型 MCP 不可运行: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("command/stdio") == true,
        )
        // 不应落盘任何插件目录（避免死插件）
        assertTrue(manager.listPlugins().isEmpty())
    }
}
