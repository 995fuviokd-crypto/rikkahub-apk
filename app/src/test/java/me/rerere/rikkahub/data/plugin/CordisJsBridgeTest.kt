package me.rerere.rikkahub.data.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.cordis.CordisHost
import me.rerere.rikkahub.data.cordis.CordisKernel
import me.rerere.rikkahub.data.cordis.LlmSeam
import me.rerere.rikkahub.data.cordis.LlmSeamResult
import me.rerere.rikkahub.data.cordis.ToolsSeam
import me.rerere.rikkahub.data.cordis.ToolSeamDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CordisJsBridgeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val fakeLlm = object : LlmSeam {
        override suspend fun infer(config: JsonObject, messages: List<UIMessage>): LlmSeamResult =
            LlmSeamResult(
                output = emptyList(),
                usage = buildJsonObject { put("promptTokens", 0) },
                provider = "fake",
                model = "fake-1",
            )
    }

    private val fakeTools = object : ToolsSeam {
        private val defs = mutableListOf(
            ToolSeamDefinition(
                name = "echo",
                description = "Echo the input",
                schema = buildJsonObject { },
                execute = { input ->
                    val text = (input as? JsonObject)?.get("text")?.let {
                        try { it.jsonPrimitive.content } catch (_: Exception) { it.toString() }
                    } ?: ""
                    JsonPrimitive("echo:$text")
                }
            )
        )
        override fun register(tool: ToolSeamDefinition): Boolean {
            if (defs.any { it.name == tool.name }) return false
            defs += tool
            return true
        }
        override fun unregister(name: String): Boolean = defs.removeAll { it.name == name }
        override fun definitions(): List<ToolSeamDefinition> = defs.toList()
        override fun get(name: String): ToolSeamDefinition? = defs.firstOrNull { it.name == name }
        override fun notifyChanged() = Unit
    }

    private fun kernelWithHost() = CordisKernel(CordisHost(llm = fakeLlm, tools = fakeTools))

    private fun bridge(pluginId: String, capabilities: List<String>): CordisJsBridge {
        val kernel = kernelWithHost()
        return CordisJsBridge(pluginId, kernel, capabilities.toSet())
    }

    @Test
    fun `llm infer returns output`() {
        val br = bridge("p1", listOf("llm"))
        val result = json.parseToJsonElement(br.seamCall("llm", "infer", "{}")).let { it as JsonObject }
        assertTrue("should be ok", result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("fake-1", result["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools list returns definitions`() {
        val br = bridge("p1", listOf("tools"))
        val result = json.parseToJsonElement(br.seamCall("tools", "list", "{}")).let { it as JsonObject }
        assertTrue("should be ok", result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("should contain echo", result["tools"]!!.jsonPrimitive.content.contains("echo"))
    }

    @Test
    fun `tools execute runs tool`() {
        val br = bridge("p1", listOf("tools"))
        val args = buildJsonObject {
            put("name", "echo")
            put("args", buildJsonObject { put("text", "hi") })
        }
        val result = json.parseToJsonElement(br.seamCall("tools", "execute", args.toString())).let { it as JsonObject }
        assertTrue("should be ok", result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("echo:hi", result["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `undeclared capability is rejected`() {
        val br = bridge("p1", listOf("llm"))
        val result = json.parseToJsonElement(br.seamCall("tools", "list", "{}")).let { it as JsonObject }
        assertTrue("should be not ok", !result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("message should mention capability", result["message"]!!.jsonPrimitive.content.contains("not declared"))
    }

    @Test
    fun `unknown seam is rejected`() {
        val br = bridge("p1", listOf("llm"))
        val result = json.parseToJsonElement(br.seamCall("nope", "x", "{}")).let { it as JsonObject }
        assertTrue("should be not ok", !result["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `agent seam runs host and returns summary`() = runBlocking {
        val host = me.rerere.rikkahub.data.agent.AgentHost(
            eventBus = me.rerere.rikkahub.data.cordis.CordisEventBus(),
            llm = object : LlmSeam {
                override suspend fun infer(config: JsonObject, messages: List<UIMessage>): LlmSeamResult {
                    return LlmSeamResult(
                        output = listOf(
                            me.rerere.ai.ui.UIMessage(
                                role = me.rerere.ai.core.MessageRole.ASSISTANT,
                                parts = listOf(
                                    me.rerere.ai.ui.UIMessagePart.Text("agent answer")
                                )
                            )
                        ),
                        provider = "fake",
                        model = "fake-1",
                    )
                }
            },
        )
        val br = CordisJsBridge("p1", kernelWithHost(), setOf("agent"), agentHost = host)
        val result = json.parseToJsonElement(br.seamCall("agent", "run", """{"prompt":"do it"}"""))
            .let { it as JsonObject }
        assertTrue("should be ok", result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("agent answer", result["assistant"]!!.jsonPrimitive.content)
    }

    @Test
    fun `agent seam requires declared capability`() {
        val br = bridge("p1", listOf("llm"))
        val result = json.parseToJsonElement(br.seamCall("agent", "run", """{"prompt":"x"}"""))
            .let { it as JsonObject }
        assertTrue("should be not ok", !result["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `plugin bridge createJsBridge returns null for unknown plugin`() = runBlocking {
        val kernel = kernelWithHost()
        val pluginBridge = CordisPluginBridge(kernel)
        assertNull("unknown plugin should have no js bridge", pluginBridge.createJsBridge("missing"))
        assertTrue("not a panel plugin", !pluginBridge.isPanelPlugin("missing"))
    }

    @Test
    fun `plugin bridge creates js bridge for panel plugin`() = runBlocking {
        val kernel = kernelWithHost()
        val pluginBridge = CordisPluginBridge(kernel)
        pluginBridge.load(
            PluginDeclaration(
                id = "panel-1",
                name = "Panel1",
                version = "1.0",
                kind = PluginDeclarationKind.PANEL,
                capabilities = listOf("llm", "tools"),
            )
        )
        assertTrue("should be a panel plugin", pluginBridge.isPanelPlugin("panel-1"))
        val jsBridge = pluginBridge.createJsBridge("panel-1")
        assertNotNull("panel plugin should have js bridge", jsBridge)
        val result = json.parseToJsonElement(jsBridge!!.seamCall("tools", "list", "{}")).let { it as JsonObject }
        assertTrue("should be ok", result["ok"]!!.jsonPrimitive.content.toBoolean())
    }
}