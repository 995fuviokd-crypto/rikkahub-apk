package me.rerere.rikkahub.data.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.cordis.CordisEventBus
import me.rerere.rikkahub.data.cordis.LlmSeam
import me.rerere.rikkahub.data.cordis.LlmSeamResult
import me.rerere.rikkahub.data.cordis.ToolsSeam
import me.rerere.rikkahub.data.cordis.ToolSeamDefinition
import me.rerere.rikkahub.data.session.Session
import me.rerere.rikkahub.data.session.SessionEvent
import me.rerere.rikkahub.data.session.TurnEndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHostTest {

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
                output = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = parts))
            )
        }
    }

    private class FakeTools(
        private val tools: List<ToolSeamDefinition>,
    ) : ToolsSeam {
        override fun register(tool: ToolSeamDefinition): Boolean = false
        override fun unregister(name: String): Boolean = false
        override fun definitions(): List<ToolSeamDefinition> = tools
        override fun get(name: String): ToolSeamDefinition? = tools.firstOrNull { it.name == name }
        override fun notifyChanged() = Unit
    }

    @Test
    fun `agent host runs basic loop`() = runBlocking {
        val host = AgentHost(
            eventBus = CordisEventBus(),
            llm = FakeLlm(listOf("hello")),
        )
        val session = host.run("hi")
        val turnEnd = session.events.find { it is SessionEvent.TurnEnd } as? SessionEvent.TurnEnd
        assertEquals(TurnEndReason.Completed, turnEnd?.reason)
        val summary = session.toAgentSummary()
        assertEquals("hello", summary["assistant"]!!.jsonPrimitive.content)
    }

    @Test
    fun `agent host executes tools from tools seam`() = runBlocking {
        val echo = ToolSeamDefinition(name = "echo", execute = { it })
        val host = AgentHost(
            eventBus = CordisEventBus(),
            llm = FakeLlm(responses = listOf("", "done"), toolCalls = listOf("c1" to "echo")),
            toolsSeam = FakeTools(listOf(echo)),
        )
        val session = host.run("use echo")

        val calls = session.events.filterIsInstance<SessionEvent.ToolCall>()
        assertEquals(1, calls.size)
        assertEquals("echo", calls[0].name)
        val results = session.events.filterIsInstance<SessionEvent.ToolResult>()
        assertEquals(1, results.size)
    }

    @Test
    fun `toAgentSummary extracts assistant text and turn count`() {
        val session = Session(
            listOf(
                SessionEvent.UserMessage(seq = 1, time = 1L, content = "hi"),
                SessionEvent.AssistantMessage(seq = 2, time = 2L, turn = 0, step = 0, content = "answer"),
                SessionEvent.TurnEnd(seq = 3, time = 3L, turn = 0, reason = TurnEndReason.Completed),
            )
        )
        val summary = session.toAgentSummary()
        assertEquals("answer", summary["assistant"]!!.jsonPrimitive.content)
        assertEquals(1, summary["turnCount"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `toToolDefinition maps seam to definition`() {
        val seam = ToolSeamDefinition(name = "x", description = "desc", execute = { it })
        val def = seam.toToolDefinition()
        assertEquals("x", def.name)
        assertEquals("desc", def.description)
    }
}