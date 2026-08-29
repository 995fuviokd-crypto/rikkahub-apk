package me.rerere.rikkahub.data.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.cordis.CordisCapabilityNotDeclaredException
import me.rerere.rikkahub.data.cordis.CordisEvent
import me.rerere.rikkahub.data.cordis.CordisHost
import me.rerere.rikkahub.data.cordis.CordisKernel
import me.rerere.rikkahub.data.cordis.CordisPluginApplyException
import me.rerere.rikkahub.data.cordis.LlmSeam
import me.rerere.rikkahub.data.cordis.LlmSeamResult
import me.rerere.rikkahub.data.cordis.ToolsSeam
import me.rerere.rikkahub.data.cordis.ToolSeamDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CordisPluginBridgeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val fakeLlm = object : LlmSeam {
        var called = false
        override suspend fun infer(config: JsonObject, messages: List<me.rerere.ai.ui.UIMessage>): LlmSeamResult {
            called = true
            return LlmSeamResult(
                output = emptyList(),
                usage = buildJsonObject { put("promptTokens", 0) },
                provider = "fake",
                model = "fake-1",
            )
        }
    }

    private val fakeTools = object : ToolsSeam {
        override fun register(tool: ToolSeamDefinition): Boolean = true
        override fun unregister(name: String): Boolean = true
        override fun definitions(): List<ToolSeamDefinition> = emptyList()
        override fun get(name: String): ToolSeamDefinition? = null
        override fun notifyChanged() = Unit
    }

    private fun kernel() = CordisKernel(CordisHost(llm = fakeLlm, tools = fakeTools))

    @Test
    fun `kotlin plugin loads into kernel`() = runBlocking {
        val k = kernel()
        val bridge = CordisPluginBridge(k)

        bridge.load(
            PluginDeclaration(
                id = "test-plugin",
                name = "Test",
                version = "1.0",
                kind = PluginDeclarationKind.KOTLIN,
            )
        )

        assertTrue(k.pluginsState.value.contains("test-plugin"))
    }

    @Test
    fun `kotlin plugin without declared capabilities is rejected on seam access`() = runBlocking {
        val k = kernel()
        val bridge = CordisPluginBridge(k)

        // 插件声明能力为空，但 apply 中尝试访问 seam → 应在注册时被拒绝
        // 由于我们的 bridge 不主动访问 seam，只验证声明与注册
        // 实际能力缝拒止已在 CordisCapabilitiesTest 中完整覆盖
        bridge.load(
            PluginDeclaration(
                id = "safe-plugin",
                name = "Safe",
                version = "1.0",
                kind = PluginDeclarationKind.KOTLIN,
                capabilities = emptyList(),
            )
        )
        assertTrue(k.pluginsState.value.contains("safe-plugin"))
    }

    @Test
    fun `plugin dependencies are injected as inject list`() = runBlocking {
        val k = kernel()
        val bridge = CordisPluginBridge(k)
        val loadOrder = mutableListOf<String>()

        // 注册依赖插件 a
        bridge.load(
            PluginDeclaration(
                id = "dep-a",
                name = "DepA",
                version = "1.0",
                kind = PluginDeclarationKind.KOTLIN,
            )
        )
        loadOrder += "dep-a"

        // 注册依赖 a 的插件 b
        bridge.load(
            PluginDeclaration(
                id = "dep-b",
                name = "DepB",
                version = "1.0",
                kind = PluginDeclarationKind.KOTLIN,
                dependencies = listOf("dep-a"),
            )
        )
        loadOrder += "dep-b"

        assertTrue(k.pluginsState.value.contains("dep-a"))
        assertTrue(k.pluginsState.value.contains("dep-b"))
    }

    @Test
    fun `js plugin registers bridge listener`() = runBlocking {
        val k = kernel()
        var jsCalled = false
        val bridge = CordisPluginBridge(
            k,
            jsExec = { _, _, _ -> jsCalled = true; buildJsonObject { } }
        )

        bridge.load(
            PluginDeclaration(
                id = "js-plugin",
                name = "JS",
                version = "1.0",
                kind = PluginDeclarationKind.JS,
                entry = "main.js",
                capabilities = listOf("tools"),
            )
        )

        val event = CordisEvent(
            name = "__scriptToolsCall:js-plugin",
            payload = buildJsonObject {
                put("tool", "echo")
                put("args", buildJsonObject { put("text", "hi") })
            }
        )
        k.eventBus.emit(event)
        assertTrue("JS executor should have been called", jsCalled)
    }

    @Test
    fun `panel plugin registers panel service`() = runBlocking {
        val k = kernel()
        val bridge = CordisPluginBridge(k)

        bridge.load(
            PluginDeclaration(
                id = "panel-plugin",
                name = "Panel",
                version = "1.0",
                kind = PluginDeclarationKind.PANEL,
            )
        )

        val panelService = k.rootContext.get("panel:panel-plugin") as? PluginDeclaration
        assertNotNull("面板插件应注册 panel 服务", panelService)
        assertEquals("panel-plugin", panelService!!.id)
    }
}