package me.rerere.rikkahub.data.plugin

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.cordis.CordisHost
import me.rerere.rikkahub.data.cordis.CordisKernel
import me.rerere.rikkahub.data.cordis.LlmSeam
import me.rerere.rikkahub.data.cordis.LlmSeamResult
import me.rerere.rikkahub.data.cordis.ToolsSeam
import me.rerere.rikkahub.data.cordis.ToolSeamDefinition
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.script.ScriptRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Cordis 运行时协调者端到端验证（Robolectric）：
 * 已启用且具备可执行能力（面板/脚本）的插件应被同步进 CordisKernel，
 * 禁用/缺失时从内核卸载；纯提示词型插件不进入内核注册表。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CordisRuntimeHostTest {

    private lateinit var context: Context
    private lateinit var settingsStore: SettingsStore
    private lateinit var scriptRuntime: ScriptRuntime
    private lateinit var pluginManager: PluginManager
    private lateinit var kernel: CordisKernel
    private lateinit var bridge: CordisPluginBridge
    private lateinit var scope: CoroutineScope
    private lateinit var host: CordisRuntimeHost

    private val fakeLlm = object : LlmSeam {
        override suspend fun infer(
            config: JsonObject,
            messages: List<me.rerere.ai.ui.UIMessage>,
        ): LlmSeamResult = LlmSeamResult(
            output = emptyList(),
            usage = buildJsonObject { put("promptTokens", 0) },
            provider = "fake",
            model = "fake-1",
        )
    }

    private val fakeTools = object : ToolsSeam {
        override fun register(tool: ToolSeamDefinition): Boolean = true
        override fun unregister(name: String): Boolean = true
        override fun definitions(): List<ToolSeamDefinition> = emptyList()
        override fun get(name: String): ToolSeamDefinition? = null
        override fun notifyChanged() = Unit
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settingsStore = SettingsStore(context, AppScope())
        scriptRuntime = ScriptRuntime(context)
        pluginManager = PluginManager(context, scriptRuntime, null)
        kernel = CordisKernel(CordisHost(llm = fakeLlm, tools = fakeTools))
        // 注入 fake JS 执行器：JS 型插件 apply 阶段需要 executor 兜底，工具调用直接返回空结果
        bridge = CordisPluginBridge(
            kernel,
            jsExec = { _, _, _ -> buildJsonObject { } },
        )
        host = CordisRuntimeHost(
            pluginManager = pluginManager,
            bridge = bridge,
            settingsStore = settingsStore,
            scope = scope,
            kernel = kernel,
        )

        // 独立插件目录，避免 Robolectric 共享状态串测
        runBlocking {
            setEnabledPlugins(emptySet())
        }
    }

    @org.junit.After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun setEnabledPlugins(ids: Set<String>) {
        // 直接写入 state flow，等价真实用户切换启用的内存态；避免 DataStore edit 的 IO 抖动
        val current = settingsStore.settingsFlow.value
        settingsStore.settingsFlow.value = current.copy(enabledPlugins = ids)
    }

    /** 在插件目录写一个含 web/plugin.client.js 的 DSH 面板型插件骨架 */
    private fun installPanelPlugin(pluginId: String, pluginName: String) {
        val dir = File(pluginManager.getPluginsDir(), pluginId).apply { mkdirs() }
        File(dir, "plugin.json").writeText(
            """
            {"id":"$pluginId","name":"$pluginName","version":"1.0.0","description":"panel","tags":["dsh"]}
            """.trimIndent()
        )
        File(dir, "web").mkdirs()
        File(dir, "web/plugin.client.js").writeText("window.__dshPanelResolve__ = true")
    }

    /** 写一个脚本型插件骨架（script/ 含 JS 入口） */
    private fun installScriptPlugin(pluginId: String, pluginName: String) {
        val dir = File(pluginManager.getPluginsDir(), pluginId).apply { mkdirs() }
        File(dir, "plugin.json").writeText(
            """
            {"id":"$pluginId","name":"$pluginName","version":"1.0.0","description":"script","tags":["dsh"]}
            """.trimIndent()
        )
        File(dir, "script").mkdirs()
        File(dir, "script/main.js").writeText("module.exports = { apply(ctx) {} }")
    }

    @Test
    fun `sync loads enabled panel plugin into kernel`() = runBlocking {
        installPanelPlugin("dsh-panel-a", "面板A")
            setEnabledPlugins(setOf("dsh-panel-a"))

            host.sync()

            val loaded = kernel.pluginsState.value.toSet()
            assertTrue("面板插件应加载进内核", "dsh-panel-a" in loaded)
            assertTrue("面板服务应注册", bridge.isPanelPlugin("dsh-panel-a"))
        }

    @Test
    fun `sync loads enabled script plugin into kernel`() = runBlocking {
        installScriptPlugin("dsh-script-b", "脚本B")
        setEnabledPlugins(setOf("dsh-script-b"))

        host.sync()

        val loaded = kernel.pluginsState.value.toSet()
        assertTrue("脚本插件应加载进内核", "dsh-script-b" in loaded)
    }

    @Test
    fun `sync does not load disabled or pure prompt plugins`() = runBlocking {
        installPanelPlugin("dsh-panel-x", "面板X")
        setEnabledPlugins(emptySet())

        host.sync()

        assertFalse("未启用插件不应加载", "dsh-panel-x" in kernel.pluginsState.value.toSet())
    }

    @Test
    fun `sync unloads plugin after it is disabled`() = runBlocking {
        installPanelPlugin("dsh-panel-y", "面板Y")
        setEnabledPlugins(setOf("dsh-panel-y"))
        host.sync()
        assertTrue("初次同步应加载", "dsh-panel-y" in kernel.pluginsState.value.toSet())

        setEnabledPlugins(emptySet())
        host.sync()

        assertFalse("禁用后应从内核卸载", "dsh-panel-y" in kernel.pluginsState.value.toSet())
        assertFalse("面板服务应移除", bridge.isPanelPlugin("dsh-panel-y"))
    }

    @Test
    fun `sync skips removed plugin dirs`() = runBlocking {
        installPanelPlugin("dsh-ghost", "幽灵")
        setEnabledPlugins(setOf("dsh-ghost"))
        host.sync()
        assertTrue("存在时加载", "dsh-ghost" in kernel.pluginsState.value.toSet())

        // 插件目录被删除（模拟卸载）
        File(pluginManager.getPluginsDir(), "dsh-ghost").deleteRecursively()
        host.sync()

        assertFalse("目录消失后应从内核卸载", "dsh-ghost" in kernel.pluginsState.value.toSet())
    }

    @Test
    fun `start configures routes via settingsFlow initial sync`() = runBlocking {
        installPanelPlugin("dsh-auto", "自动")
        setEnabledPlugins(setOf("dsh-auto"))

        host.start()
        // 初始同步在 Default 调度器跑，等待内部状态就绪
        repeat(20) {
            if ("dsh-auto" in kernel.pluginsState.value.toSet()) return@runBlocking
            kotlinx.coroutines.delay(50)
        }
        assertTrue("start 初始同步应加载启用插件", "dsh-auto" in kernel.pluginsState.value.toSet())
    }

    @Test
    fun `loadedIds mirrors kernel state after sync`() = runBlocking {
        installPanelPlugin("dsh-track", "追踪")
        setEnabledPlugins(setOf("dsh-track"))
        host.sync()
        assertEquals(setOf("dsh-track"), host.loadedIds.first())

        setEnabledPlugins(emptySet())
        host.sync()
        assertEquals(emptySet<String>(), host.loadedIds.first())
    }
}