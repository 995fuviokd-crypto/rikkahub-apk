package me.rerere.rikkahub.data.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.cordis.CordisEventBus
import me.rerere.rikkahub.data.cordis.LlmSeam
import me.rerere.rikkahub.data.cordis.LlmSeamResult
import me.rerere.rikkahub.data.cordis.SystemPromptSeam
import me.rerere.rikkahub.data.session.SessionEvent
import me.rerere.rikkahub.data.session.TurnEndReason
import me.rerere.rikkahub.data.tools.ToolDefinition
import me.rerere.rikkahub.data.tools.ToolPipeline
import me.rerere.rikkahub.data.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class FakeLlm(
        private val responses: List<String>,
        private val toolCalls: List<Pair<String, String>> = emptyList(),
    ) : LlmSeam {
        var callCount = 0
        override suspend fun infer(config: JsonObject, messages: List<UIMessage>): LlmSeamResult {
            val idx = callCount++
            val text = if (idx < responses.size) responses[idx] else responses.last()
            val parts = mutableListOf<UIMessagePart>()
            parts.add(UIMessagePart.Text(text))
            for ((id, name) in toolCalls.filterIndexed { i, _ -> i == idx }) {
                parts.add(
                    UIMessagePart.Tool(
                        toolCallId = id,
                        toolName = name,
                        input = "{\"x\":1}",
                    )
                )
            }
            return LlmSeamResult(
                output = listOf(
                    UIMessage(role = MessageRole.ASSISTANT, parts = parts)
                ),
                usage = buildJsonObject {
                    put("promptTokens", JsonPrimitive(10))
                    put("completionTokens", JsonPrimitive(5))
                },
                provider = "fake",
                model = "fake-1",
            )
        }
    }

    @Test
    fun `basic text response produces complete session`() = runBlocking {
        val bus = CordisEventBus()
        val pipeline = ToolPipeline(bus)
        val registry = ToolRegistry(bus)
        val loop = AgentLoop(pipeline, registry)

        val llm = FakeLlm(listOf("Hello, world!"))
        val session = loop.run("hi", llm)

        assertTrue("应包含 TurnStart", session.events.any { it is SessionEvent.TurnStart })
        assertTrue("应包含 UserMessage", session.events.any { it is SessionEvent.UserMessage })
        assertTrue(
            "应包含 AssistantMessage",
            session.events.any { it is SessionEvent.AssistantMessage }
        )
        val turnEnd = session.events.find { it is SessionEvent.TurnEnd } as? SessionEvent.TurnEnd
        assertNotNull("应包含 TurnEnd", turnEnd)
        assertEquals(TurnEndReason.Completed, turnEnd!!.reason)

        val history = session.deriveHistory()
        assertTrue("应产出至少 2 条历史消息", history.size >= 2)
        assertEquals("Hello, world!", history.last().toText())
    }

    @Test
    fun `tool calling loop executes tools and continues`() = runBlocking {
        val bus = CordisEventBus()
        val pipeline = ToolPipeline(bus)
        val registry = ToolRegistry(bus)
        val loop = AgentLoop(pipeline, registry)

        registry.register(
            ToolDefinition(
                name = "echo",
                description = "echo input",
                execute = { input -> input },
            )
        )

        val llm = FakeLlm(
            responses = listOf("", "done"),
            toolCalls = listOf("c1" to "echo"),
        )
        val session = loop.run("use echo", llm)

        val toolCalls = session.events.filterIsInstance<SessionEvent.ToolCall>()
        assertEquals(1, toolCalls.size)
        assertEquals("echo", toolCalls[0].name)

        val toolResults = session.events.filterIsInstance<SessionEvent.ToolResult>()
        assertEquals(1, toolResults.size)
        assertEquals("echo", toolResults[0].name)

        val history = session.deriveHistory()
        assertEquals("done", history.last().toText())
    }

    @Test
    fun `unknown tool produces error result`() = runBlocking {
        val bus = CordisEventBus()
        val pipeline = ToolPipeline(bus)
        val registry = ToolRegistry(bus)
        val loop = AgentLoop(pipeline, registry)

        val llm = FakeLlm(
            responses = listOf("ok"),
            toolCalls = listOf("c1" to "nonexistent"),
        )
        val session = loop.run("use unknown", llm)

        val toolResults = session.events.filterIsInstance<SessionEvent.ToolResult>()
        assertEquals(1, toolResults.size)
        assertTrue(toolResults[0].message.contains("unknown tool"))
    }

    @Test
    fun `steward judge continues loop when true`() = runBlocking {
        val bus = CordisEventBus()
        val pipeline = ToolPipeline(bus)
        val registry = ToolRegistry(bus)
        val loop = AgentLoop(pipeline, registry)

        var judgeCalls = 0
        val llm = FakeLlm(listOf("step1", "step2", "step3"))
        val session = loop.run(
            "multi step",
            llm,
            stewardJudge = {
                judgeCalls++
                judgeCalls < 3
            }
        )

        assertEquals(3, judgeCalls)
        val assistantMessages = session.events.filterIsInstance<SessionEvent.AssistantMessage>()
        assertEquals(3, assistantMessages.size)
    }

    @Test
    fun `system prompt is included in request header`() = runBlocking {
        val bus = CordisEventBus()
        val pipeline = ToolPipeline(bus)
        val registry = ToolRegistry(bus)
        val loop = AgentLoop(pipeline, registry)

        val llm = FakeLlm(listOf("ok"))
        val systemPrompt = object : SystemPromptSeam {
            override fun addFragment(id: String, position: Int, content: () -> String): Int = 0
            override fun removeFragment(id: String) = Unit
            override suspend fun assemble(): String = "You are a helpful assistant."
        }

        val session = loop.run("hi", llm, systemPrompt = systemPrompt)

        val header = session.events.find { it is SessionEvent.RequestHeader } as? SessionEvent.RequestHeader
        assertNotNull(header)
        assertEquals("You are a helpful assistant.", header!!.system)
    }

    @Test
    fun `cancelled loop records interrupted`() = runBlocking {
        val bus = CordisEventBus()
        val pipeline = ToolPipeline(bus)
        val registry = ToolRegistry(bus)
        val loop = AgentLoop(pipeline, registry)

        val llm = object : LlmSeam {
            override suspend fun infer(config: JsonObject, messages: List<UIMessage>): LlmSeamResult {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }

        try {
            loop.run("hi", llm)
            org.junit.Assert.fail("should have thrown SessionCancelledException")
        } catch (e: SessionCancelledException) {
            val session = e.session
            val turnEnd = session.events.find { it is SessionEvent.TurnEnd } as? SessionEvent.TurnEnd
            assertNotNull("取消时应保留 TurnEnd", turnEnd)
            assertEquals(TurnEndReason.Interrupted, turnEnd!!.reason)
        }
    }
}