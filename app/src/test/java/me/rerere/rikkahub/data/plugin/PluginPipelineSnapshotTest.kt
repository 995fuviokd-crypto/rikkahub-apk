package me.rerere.rikkahub.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 管线冷数据快照一致性测试（design.md D2.4 / R6.2 / 9.4）：
 * - snapshot 与安装目录对账一致：enabledPlugins = 安装 ∩ 启用
 * - Hook 清单索引：hook 名 → 启用插件 id（稳定排序）
 * - 系统提示组装：含配置注入、跳过空提示
 * - 禁用插件即时移除（启用集合变化 → 快照变化）
 */
class PluginPipelineSnapshotTest {

    private fun info(
        id: String,
        systemPrompt: String = "",
        hooks: List<PluginHook> = emptyList(),
    ) = PluginInfo(id = id, name = "P-$id", version = "1.0.0", systemPrompt = systemPrompt, hooks = hooks)

    private fun hook(name: String) = PluginHook(name = name, description = "")

    @Test
    fun `snapshot reconciles installed and enabled sets`() {
        val installed = listOf(
            InstalledPlugin("a", info("a"), PluginStatus.INSTALLED),
            InstalledPlugin("b", info("b"), PluginStatus.INSTALLED),
            InstalledPlugin("c", info("c"), PluginStatus.INSTALLED),
        )
        val snapshot = PluginManager.buildPipelineSnapshot(installed, enabled = setOf("a", "c"))
        assertEquals(setOf("a", "c"), snapshot.enabledPlugins)
        assertFalse("b" in snapshot.enabledPlugins)
    }

    @Test
    fun `broken or missing info plugins are excluded`() {
        val installed = listOf(
            InstalledPlugin("ok", info("ok"), PluginStatus.INSTALLED),
            InstalledPlugin("bad", null, PluginStatus.BROKEN),
        )
        val snapshot = PluginManager.buildPipelineSnapshot(installed, enabled = setOf("ok", "bad"))
        assertEquals(setOf("ok"), snapshot.enabledPlugins)
    }

    @Test
    fun `hook handlers indexed by hook name with stable plugin order`() {
        val installed = listOf(
            InstalledPlugin("z-plugin", info("z-plugin", hooks = listOf(hook(PluginHook.REQUEST_BEFORE_SEND))), PluginStatus.INSTALLED),
            InstalledPlugin("a-plugin", info("a-plugin", hooks = listOf(hook(PluginHook.REQUEST_BEFORE_SEND), hook(PluginHook.TITLE_AFTER_GENERATE))), PluginStatus.INSTALLED),
        )
        val snapshot = PluginManager.buildPipelineSnapshot(installed, enabled = setOf("z-plugin", "a-plugin"))
        assertEquals(
            listOf("a-plugin", "z-plugin"),
            snapshot.hookHandlers[PluginHook.REQUEST_BEFORE_SEND],
        )
        assertEquals(listOf("a-plugin"), snapshot.hookHandlers[PluginHook.TITLE_AFTER_GENERATE])
        assertTrue(snapshot.hookHandlers[PluginHook.MESSAGE_BEFORE_SEND].isNullOrEmpty())
    }

    @Test
    fun `system prompts assembled with config injection and blank skip`() {
        val installed = listOf(
            InstalledPlugin("a", info("a", systemPrompt = "prompt-a"), PluginStatus.INSTALLED),
            InstalledPlugin("b", info("b", systemPrompt = "  "), PluginStatus.INSTALLED),
            InstalledPlugin("c", info("c", systemPrompt = "prompt-c"), PluginStatus.INSTALLED),
        )
        val snapshot = PluginManager.buildPipelineSnapshot(
            installed,
            enabled = setOf("a", "b", "c"),
            configJson = { info -> if (info.id == "c") """{"k":"v"}""" else null },
        )
        // 稳定排序 + 空提示跳过 + 配置注入
        assertEquals(listOf("prompt-a", "prompt-c\n\n<插件配置 c>：{\"k\":\"v\"}\n</插件配置>"), snapshot.systemPrompts)
    }

    @Test
    fun `disabling a plugin removes its tools and hooks immediately`() {
        val installed = listOf(
            InstalledPlugin("a", info("a", systemPrompt = "pa", hooks = listOf(hook("request:beforeSend"))), PluginStatus.INSTALLED),
        )
        val withA = PluginManager.buildPipelineSnapshot(
            installed, enabled = setOf("a"),
            tools = { id -> if (id == "a") listOf("t1", "t2") else emptyList() },
        )
        assertEquals(listOf(PluginToolsEntry("a", "P-a", listOf("t1", "t2"))), withA.tools)
        assertEquals(setOf("a"), withA.enabledPlugins)
        assertEquals(listOf("a"), withA.hookHandlers["request:beforeSend"])

        // R6.2：禁用后同一安装目录的快照即时移除工具与 Hook
        val withoutA = PluginManager.buildPipelineSnapshot(
            installed, enabled = emptySet(),
            tools = { id -> if (id == "a") listOf("t1", "t2") else emptyList() },
        )
        assertTrue(withoutA.tools.isEmpty())
        assertTrue(withoutA.systemPrompts.isEmpty())
        assertTrue(withoutA.hookHandlers.isEmpty())
        assertTrue(withoutA.enabledPlugins.isEmpty())
    }

    @Test
    fun `empty enabled set yields empty snapshot`() {
        val installed = listOf(InstalledPlugin("a", info("a", systemPrompt = "p"), PluginStatus.INSTALLED))
        val snapshot = PluginManager.buildPipelineSnapshot(installed, enabled = emptySet())
        assertTrue(snapshot.enabledPlugins.isEmpty())
        assertTrue(snapshot.systemPrompts.isEmpty())
        assertTrue(snapshot.tools.isEmpty())
    }
}
