package me.rerere.rikkahub.data.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    private var seq = 0L
    private var now = 1_700_000_000_000L

    private fun s(): Long = ++seq
    private fun t(): Long = now

    private fun buildTurn1Turn2(): Session {
        val events = listOf(
            SessionEvent.TurnStart(s(), t(), turn = 0),
            SessionEvent.UserMessage(s(), t(), "hello"),
            SessionEvent.StepStart(s(), t(), turn = 0, step = 0),
            SessionEvent.AssistantChunk(
                s(), t(), turn = 0, step = 0,
                StreamChunk.TextDelta(id = "a1", text = "hi "),
            ),
            SessionEvent.AssistantChunk(
                s(), t(), turn = 0, step = 0,
                StreamChunk.TextDelta(id = "a1", text = "there"),
            ),
            SessionEvent.StepEnd(s(), t(), turn = 0, step = 0),
            SessionEvent.TurnEnd(s(), t(), turn = 0, reason = TurnEndReason.Completed),
            SessionEvent.TurnStart(s(), t(), turn = 1),
            SessionEvent.UserMessage(s(), t(), "again"),
            SessionEvent.StepStart(s(), t(), turn = 1, step = 0),
            SessionEvent.AssistantMessage(
                s(), t(), turn = 1, step = 0,
                content = "replied",
                reasoning = "thinking",
            ),
            SessionEvent.StepEnd(s(), t(), turn = 1, step = 0),
            SessionEvent.TurnEnd(s(), t(), turn = 1, reason = TurnEndReason.Completed),
        )
        return Session(events)
    }

    @Test
    fun `empty session derives empty history`() {
        val session = Session()
        assertTrue(session.isEncryptedEmpty)
        assertEquals(0, session.deriveHistory().size)
        assertEquals(0, session.turnCount())
    }

    @Test
    fun `text chunks are merged into one assistant message`() {
        val history = buildTurn1Turn2().deriveHistory()
        assertEquals(4, history.size)

        assertEquals(MessageRole.USER, history[0].role)
        assertEquals("hello", history[0].toText())

        assertEquals(MessageRole.ASSISTANT, history[1].role)
        assertEquals("hi there", history[1].toText())

        assertEquals(MessageRole.USER, history[2].role)
        assertEquals("again", history[2].toText())
    }

    @Test
    fun `assistant message with reasoning keeps reasoning and text parts`() {
        val history = buildTurn1Turn2().deriveHistory()
        val assistant = history[3]
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        val reasoning = assistant.parts.filterIsInstance<UIMessagePart.Reasoning>()
        val text = assistant.parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(1, reasoning.size)
        assertEquals("thinking", reasoning[0].reasoning)
        assertEquals("replied", text[0].text)
    }

    @Test
    fun `turn count counts completed turns`() {
        val session = buildTurn1Turn2()
        assertEquals(2, session.turnCount())
        assertEquals(13, session.length())
    }

    @Test
    fun `tool call and result are attached to assistant message`() {
        val events = listOf(
            SessionEvent.UserMessage(s(), t(), "sum 1 2"),
            SessionEvent.StepStart(s(), t(), turn = 0, step = 0),
            SessionEvent.ToolCall(s(), t(), turn = 0, step = 0, callId = "c1", name = "sum", arguments = "{\"a\":1,\"b\":2}"),
            SessionEvent.ToolResult(s(), t(), turn = 0, step = 0, callId = "c1", name = "sum", message = "3"),
            SessionEvent.StepEnd(s(), t(), turn = 0, step = 0),
        )
        val history = Session(events).deriveHistory()
        assertEquals(2, history.size)

        val assistant = history[1]
        val tool = assistant.parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, tool.size)
        assertEquals("c1", tool[0].toolCallId)
        assertEquals("sum", tool[0].toolName)
        assertTrue(tool[0].isExecuted)
        assertEquals("3", (tool[0].output[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `request header derivation returns last header`() {
        val header1 = SessionEvent.RequestHeader(
            s(), t(),
            config = JsonObject(emptyMap()),
            system = "sys-v1",
            tools = listOf("a"),
        )
        val header2 = SessionEvent.RequestHeader(
            s(), t(),
            config = JsonObject(emptyMap()),
            system = "sys-v2",
            tools = listOf("a", "b"),
            reason = RequestHeaderReason.Change,
        )
        val session = Session(listOf(header1, header2))
        val derived = session.deriveRequestHeader()
        assertNotNull(derived)
        assertEquals("sys-v2", derived!!.system)
        assertEquals(2, derived.tools?.size)
    }

    @Test
    fun `append and combined preserve ordering`() {
        val a = Session(listOf(SessionEvent.EndSeed(s(), t())))
        val b = a.append(SessionEvent.UserMessage(s(), t(), "x"))
        assertEquals(2, b.length())
        assertTrue(b.events[1] is SessionEvent.UserMessage)

        val c = b.combined(Session(listOf(SessionEvent.UserMessage(s(), t(), "y"))))
        assertEquals(3, c.length())
        assertEquals("y", (c.events[2] as SessionEvent.UserMessage).content)
    }

    @Test
    fun `deriveHistory with chunks only is deterministic`() {
        val session = buildTurn1Turn2()
        val first = session.deriveHistory()
        val second = session.deriveHistory()
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertEquals(first[i].role, second[i].role)
            assertEquals(first[i].toText(), second[i].toText())
        }
    }

    @Test
    fun `injected user message still appears in history`() {
        val events = listOf(
            SessionEvent.UserMessage(s(), t(), "context", source = UserMessageSource.Injected),
        )
        val history = Session(events).deriveHistory()
        assertEquals(1, history.size)
        assertEquals("context", history[0].toText())
        assertFalse(history[0].isSynthetic)
    }

    @Test
    fun `session event polymorphic serialization round trip`() {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val events: List<SessionEvent> = listOf(
            SessionEvent.TurnStart(1, t(), turn = 0),
            SessionEvent.UserMessage(2, t(), "hi"),
            SessionEvent.ToolCall(3, t(), turn = 0, step = 0, callId = "c", name = "sum", arguments = "{}"),
            SessionEvent.ToolResult(4, t(), turn = 0, step = 0, callId = "c", name = "sum", message = "3"),
            SessionEvent.TurnEnd(5, t(), turn = 0, reason = TurnEndReason.Completed),
            SessionEvent.RequestHeader(
                6, t(),
                config = JsonObject(emptyMap()),
                system = "sys",
                tools = listOf("sum"),
            ),
        )
        for (event in events) {
            val encoded = json.encodeToString(SessionEventWrapperForTest(event))
            val decoded = json.decodeFromString<SessionEventWrapperForTest>(encoded)
            assertEquals(event, decoded.event)
        }
    }
}

@kotlinx.serialization.Serializable
private data class SessionEventWrapperForTest(val event: SessionEvent)