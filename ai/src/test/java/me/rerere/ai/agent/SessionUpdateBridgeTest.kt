package me.rerere.ai.agent

import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionUpdateBridgeTest {

    private fun v1Chunk(text: String) = AcpSessionUpdate(
        sessionUpdate = "agent_message_chunk",
        content = AcpTextContent(type = "text", text = text),
    )

    private fun v2Delta(vararg texts: String) = AcpSessionUpdate(
        sessionUpdate = "message_update",
        delta = texts.map { AcpContentChunk(type = "text_delta", text = it) },
    )

    @Test
    fun `first v1 chunk starts text stream`() {
        val bridge = SessionUpdateBridge("id")
        val first = bridge.translate(v1Chunk("Hello"))
        assertEquals(StreamChunk.TextStart("id"), first)
    }

    @Test
    fun `subsequent v1 chunks are text deltas`() {
        val bridge = SessionUpdateBridge("id")
        bridge.translate(v1Chunk("Hello"))
        val delta = bridge.translate(v1Chunk(", world"))
        assertEquals(StreamChunk.TextDelta("id", ", world"), delta)
    }

    @Test
    fun `v2 message_update deltas are merged into one delta`() {
        val bridge = SessionUpdateBridge("id")
        val first = bridge.translate(v2Delta("你", "好"))
        assertEquals(StreamChunk.TextStart("id"), first)
        val second = bridge.translate(v2Delta("世", "界"))
        assertEquals(StreamChunk.TextDelta("id", "世界"), second)
    }

    @Test
    fun `v1 and v2 updates interleave`() {
        val bridge = SessionUpdateBridge("id")
        bridge.translate(v1Chunk("A"))
        val delta = bridge.translate(v2Delta("B", "C"))
        assertEquals(StreamChunk.TextDelta("id", "BC"), delta)
    }

    @Test
    fun `non-text updates are ignored`() {
        val bridge = SessionUpdateBridge("id")
        assertNull(
            bridge.translate(
                AcpSessionUpdate(
                    sessionUpdate = "message_update",
                    delta = listOf(AcpContentChunk(type = "tool_call", text = null)),
                )
            )
        )
        assertNull(
            bridge.translate(
                AcpSessionUpdate(
                    sessionUpdate = "session/end",
                    content = AcpTextContent(type = "text", text = "ignored"),
                )
            )
        )
    }

    @Test
    fun `finish emits TextEnd only after a stream started`() {
        val idle = SessionUpdateBridge("id")
        assertNull(idle.finish())

        val started = SessionUpdateBridge("id")
        started.translate(v1Chunk("X"))
        assertEquals(StreamChunk.TextEnd("id"), started.finish())
        assertNull(started.finish())
    }

    @Test
    fun `extractFinalText reads session end summary`() {
        val update = AcpSessionUpdate(
            sessionUpdate = "session/end",
            delta = listOf(AcpContentChunk(type = "text", text = "final answer")),
        )
        assertEquals("final answer", extractFinalText(update))
        assertNull(extractFinalText(v1Chunk("not an end")))
    }
}
