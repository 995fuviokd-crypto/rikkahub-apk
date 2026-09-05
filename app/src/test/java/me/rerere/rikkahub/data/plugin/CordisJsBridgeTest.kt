package me.rerere.rikkahub.data.plugin

import kotlinx.coroutines.cancel
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
        val br = CordisJsBridge("p1", kernelWithHost(), setOf("agent"), agentHost = { host })
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

    // ---- D1.2 能力缝接线（R2.1/R2.4）----

    @Test
    fun `llm infer passes messages and model through`() {
        var seenConfig: JsonObject? = null
        var seenMessages: List<UIMessage>? = null
        val recordingLlm = object : LlmSeam {
            override suspend fun infer(config: JsonObject, messages: List<UIMessage>): LlmSeamResult {
                seenConfig = config
                seenMessages = messages
                return LlmSeamResult(
                    output = listOf(
                        UIMessage(
                            role = me.rerere.ai.core.MessageRole.ASSISTANT,
                            parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("answer")),
                        )
                    ),
                    provider = "fake",
                    model = "fake-1",
                )
            }
        }
        val kernel = CordisKernel(CordisHost(llm = recordingLlm, tools = fakeTools))
        val br = CordisJsBridge("p1", kernel, setOf("llm"))

        val args = buildJsonObject {
            put(
                "messages",
                kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("text", "be brief") })
                    add(buildJsonObject { put("role", "user"); put("text", "hello") })
                },
            )
            put("model", "11111111-1111-1111-1111-111111111111")
            put("temperature", 0.7)
        }
        val result = json.parseToJsonElement(br.seamCall("llm", "infer", args.toString())).let { it as JsonObject }
        assertTrue("should be ok", result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("answer", result["output"]!!.jsonPrimitive.content)

        val messages = seenMessages!!
        assertEquals(2, messages.size)
        assertEquals(me.rerere.ai.core.MessageRole.SYSTEM, messages[0].role)
        assertEquals("be brief", messages[0].parts.first().let { it as me.rerere.ai.ui.UIMessagePart.Text }.text)
        assertEquals(me.rerere.ai.core.MessageRole.USER, messages[1].role)

        val config = seenConfig!!
        assertEquals(
            "11111111-1111-1111-1111-111111111111",
            config["modelId"]!!.jsonPrimitive.content,
        )
        assertEquals("0.7", config["temperature"]!!.jsonPrimitive.content)
        assertTrue("messages should not leak into config", config["messages"] == null)
    }

    @Test
    fun `llm infer falls back to prompt as single user message`() {
        var seenMessages: List<UIMessage>? = null
        val recordingLlm = object : LlmSeam {
            override suspend fun infer(config: JsonObject, messages: List<UIMessage>): LlmSeamResult {
                seenMessages = messages
                return LlmSeamResult(output = emptyList(), provider = "fake", model = "fake-1")
            }
        }
        val kernel = CordisKernel(CordisHost(llm = recordingLlm, tools = fakeTools))
        val br = CordisJsBridge("p1", kernel, setOf("llm"))
        br.seamCall("llm", "infer", """{"prompt":"legacy text"}""")
        val messages = seenMessages!!
        assertEquals(1, messages.size)
        assertEquals(me.rerere.ai.core.MessageRole.USER, messages[0].role)
    }

    @Test
    fun `undeclared capability returns structured reason`() {
        val br = bridge("p1", listOf("llm"))
        val result = json.parseToJsonElement(br.seamCall("tools", "list", "{}")).let { it as JsonObject }
        assertTrue("should be not ok", !result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("not_declared", result["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing seam impl returns unimplemented reason`() {
        // kernel 无 tools 缝（host.tools = null）→ 已声明但未实现
        val kernel = CordisKernel(CordisHost(llm = fakeLlm))
        val br = CordisJsBridge("p1", kernel, setOf("tools"))
        val result = json.parseToJsonElement(br.seamCall("tools", "list", "{}")).let { it as JsonObject }
        assertTrue("should be not ok", !result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("unimplemented", result["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lazy dependency failure degrades to structured error`() {
        // 提供者抛异常 → 结构化错误而非崩溃（D1.1 惰性化语义）
        val br = CordisJsBridge(
            "p1",
            kernelWithHost(),
            setOf("agent"),
            agentHost = { throw IllegalStateException("koin boom") },
        )
        val result = json.parseToJsonElement(br.seamCall("agent", "run", """{"prompt":"x"}"""))
            .let { it as JsonObject }
        assertTrue("should be not ok", !result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("unimplemented", result["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sessions send without chat service returns unimplemented`() {
        val br = CordisJsBridge(
            "p1",
            kernelWithHost(),
            setOf("sessions"),
            conversationRepo = { throw IllegalStateException("repo boom") },
        )
        val result = json.parseToJsonElement(
            br.seamCall("sessions", "list", """{"limit":5}""")
        ).let { it as JsonObject }
        assertTrue("should be not ok", !result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("unimplemented", result["reason"]!!.jsonPrimitive.content)
    }

    // ---- R3.1 异步调用协议（seamCallAsync）----

    private inner class AsyncHarness(
        capabilities: Set<String>,
        kernel: CordisKernel = CordisKernel(CordisHost(llm = fakeLlm, tools = fakeTools)),
    ) {
        val dispatched = java.util.concurrent.CopyOnWriteArrayList<String>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)

        val bridge = CordisJsBridge(
            "p1",
            kernel,
            capabilities,
            asyncScope = scope,
            resultDispatcher = { js ->
                dispatched += js
            },
        )

        fun awaitDispatch(count: Int, timeoutMs: Long = 5000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (dispatched.size < count && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            check(dispatched.size >= count) { "timeout waiting for $count dispatches, got ${dispatched.size}" }
        }
    }

    @Test
    fun `seamCallAsync returns callId and dispatches result`() {
        val h = AsyncHarness(setOf("llm"))
        val ack = json.parseToJsonElement(h.bridge.seamCallAsync("llm", "infer", "{}")).let { it as JsonObject }
        assertTrue("should be accepted", ack["ok"]!!.jsonPrimitive.content.toBoolean())
        val callId = ack["callId"]!!.jsonPrimitive.content

        h.awaitDispatch(1)
        val js = h.dispatched.first()
        assertTrue("js should target onResult", js.startsWith("window.CordisBridge.onResult("))
        assertTrue("js should carry callId", js.contains(callId))
        assertTrue("js should carry model", js.contains("fake-1"))
        h.scope.cancel()
    }

    @Test
    fun `seamCallAsync concurrent calls pair results by callId`() {
        val h = AsyncHarness(setOf("tools"))
        val args = buildJsonObject {
            put("name", "echo")
            put("args", buildJsonObject { put("text", "async") })
        }
        val ack1 = json.parseToJsonElement(h.bridge.seamCallAsync("tools", "execute", args.toString()))
            .let { it as JsonObject }
        val ack2 = json.parseToJsonElement(h.bridge.seamCallAsync("tools", "execute", args.toString()))
            .let { it as JsonObject }
        val id1 = ack1["callId"]!!.jsonPrimitive.content
        val id2 = ack2["callId"]!!.jsonPrimitive.content
        assertTrue("callIds must differ", id1 != id2)

        h.awaitDispatch(2)
        // 每个 callId 恰好配对一个携带各自结果的回推
        listOf(id1, id2).forEach { id ->
            val matches = h.dispatched.filter { it.contains(id) }
            assertEquals("callId $id should pair exactly one dispatch", 1, matches.size)
            assertTrue("dispatch for $id should carry tool result", matches.single().contains("echo:async"))
        }
        h.scope.cancel()
    }

    @Test
    fun `seamCallAsync undeclared capability rejects synchronously without dispatch`() {
        val h = AsyncHarness(setOf("llm"))
        val ack = json.parseToJsonElement(h.bridge.seamCallAsync("tools", "list", "{}")).let { it as JsonObject }
        assertTrue("should be not ok", !ack["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("not_declared", ack["reason"]!!.jsonPrimitive.content)
        // 无异步副作用：等待短暂窗口确认 dispatcher 未被调用
        Thread.sleep(150)
        assertTrue("no dispatch expected", h.dispatched.isEmpty())
        h.scope.cancel()
    }

    @Test
    fun `seamCallAsync without scope returns unimplemented`() {
        val br = CordisJsBridge("p1", kernelWithHost(), setOf("llm"))
        val ack = json.parseToJsonElement(br.seamCallAsync("llm", "infer", "{}")).let { it as JsonObject }
        assertTrue("should be not ok", !ack["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("unimplemented", ack["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `seamCallAsync execution failure dispatches structured error`() {
        // seam 执行抛异常 → 回推 execution_failed（JS 侧 Promise 正常 reject）
        val badLlm = object : LlmSeam {
            override suspend fun infer(config: JsonObject, messages: List<UIMessage>): LlmSeamResult =
                throw IllegalStateException("infer boom")
        }
        val h = AsyncHarness(setOf("llm"), CordisKernel(CordisHost(llm = badLlm)))
        val ack = json.parseToJsonElement(h.bridge.seamCallAsync("llm", "infer", "{}"))
            .let { it as JsonObject }
        assertTrue("should be accepted", ack["ok"]!!.jsonPrimitive.content.toBoolean())

        h.awaitDispatch(1)
        val js = h.dispatched.first()
        assertTrue("dispatch should carry execution_failed", js.contains("execution_failed"))
        assertTrue("dispatch should carry failure message", js.contains("infer boom"))
        h.scope.cancel()
    }

    @Test
    fun `events subscribe pushes via dispatcher and release cleans up`() = runBlocking {
        val appBus = me.rerere.rikkahub.data.event.AppEventBus()
        val bus = CordisHostEventBus(
            appBus,
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
            ),
        )
        bus.start()
        val pushed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val br = CordisJsBridge(
            "p1",
            kernelWithHost(),
            setOf("events"),
            eventBus = bus,
            resultDispatcher = { js -> pushed += js },
        )

        // R3.2 订阅：事件到达经 dispatcher 主动推 onEvent
        val sub = json.parseToJsonElement(br.seamCall("events", "subscribe", """{"topics":["chat."]}"""))
            .let { it as JsonObject }
        assertTrue("subscribe should be ok", sub["ok"]!!.jsonPrimitive.content.toBoolean())

        appBus.tryEmit(me.rerere.rikkahub.data.event.AppEvent.ChatGenerationEnded(kotlin.uuid.Uuid.random(), "a", "p"))
        assertEquals("one push expected", 1, pushed.size)
        assertTrue("push should target onEvent", pushed[0].startsWith("window.CordisBridge.onEvent("))
        assertTrue("push should carry seq envelope", pushed[0].contains("chat.generationEnded"))

        // 页面离开：release 解绑订阅，后续事件不再推送
        br.release()
        appBus.tryEmit(me.rerere.rikkahub.data.event.AppEvent.ChatGenerationEnded(kotlin.uuid.Uuid.random(), "b", "p2"))
        assertEquals("no push after release", 1, pushed.size)
    }
}