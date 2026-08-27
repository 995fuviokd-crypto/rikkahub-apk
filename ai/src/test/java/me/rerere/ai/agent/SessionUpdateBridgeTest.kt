package me.rerere.ai.agent

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val chunks = bridge.translate(v1Chunk("Hello"))
        assertEquals(
            listOf<StreamChunk>(StreamChunk.TextStart("id"), StreamChunk.TextDelta("id", "Hello")),
            chunks,
        )
    }

    @Test
    fun `subsequent v1 chunks are text deltas`() {
        val bridge = SessionUpdateBridge("id")
        bridge.translate(v1Chunk("Hello"))
        val chunks = bridge.translate(v1Chunk(", world"))
        assertEquals(listOf<StreamChunk>(StreamChunk.TextDelta("id", ", world")), chunks)
    }

    @Test
    fun `v2 message_update deltas are merged into one delta`() {
        val bridge = SessionUpdateBridge("id")
        val first = bridge.translate(v2Delta("你", "好"))
        assertEquals(
            listOf<StreamChunk>(StreamChunk.TextStart("id"), StreamChunk.TextDelta("id", "你好")),
            first,
        )
        val second = bridge.translate(v2Delta("世", "界"))
        assertEquals(listOf<StreamChunk>(StreamChunk.TextDelta("id", "世界")), second)
    }

    @Test
    fun `v1 and v2 updates interleave`() {
        val bridge = SessionUpdateBridge("id")
        bridge.translate(v1Chunk("A"))
        val chunks = bridge.translate(v2Delta("B", "C"))
        assertEquals(listOf<StreamChunk>(StreamChunk.TextDelta("id", "BC")), chunks)
    }

    @Test
    fun `non-text updates produce no chunks`() {
        val bridge = SessionUpdateBridge("id")
        assertTrue(
            bridge.translate(
                AcpSessionUpdate(
                    sessionUpdate = "message_update",
                    delta = listOf(AcpContentChunk(type = "tool_call", text = null)),
                )
            ).isEmpty()
        )
        assertTrue(
            bridge.translate(
                AcpSessionUpdate(
                    sessionUpdate = "session/end",
                    content = AcpTextContent(type = "text", text = "ignored"),
                )
            ).isEmpty()
        )
    }

    @Test
    fun `agent thought chunks stream as reasoning`() {
        val bridge = SessionUpdateBridge("id")
        val start = bridge.translate(
            AcpSessionUpdate(sessionUpdate = "agent_thought_chunk", content = AcpTextContent(text = "thinking"))
        )
        assertEquals(2, start.size)
        assertEquals(StreamChunk.ReasoningStart(id = "id"), start[0])
        assertEquals(StreamChunk.ReasoningDelta(id = "id", text = "thinking"), start[1])

        val more = bridge.translate(
            AcpSessionUpdate(sessionUpdate = "agent_thought_chunk", content = AcpTextContent(text = " more"))
        )
        assertEquals(listOf<StreamChunk>(StreamChunk.ReasoningDelta(id = "id", text = " more")), more)

        val end = bridge.finish()
        assertTrue(StreamChunk.ReasoningEnd(id = "id") in end)
    }

    @Test
    fun `tool_call starts a server tool card`() {
        val bridge = SessionUpdateBridge("id")
        val chunks = bridge.translate(
            AcpSessionUpdate(
                sessionUpdate = "tool_call",
                toolCallId = "t1",
                title = "Read file src/main.kt",
                kind = "read",
            )
        )
        assertEquals(1, chunks.size)
        val start = chunks[0] as StreamChunk.ServerToolStart
        assertEquals("t1", start.id)
        assertEquals("Read file src/main.kt", start.toolName)
        assertEquals(ServerToolStatus.IN_PROGRESS, statusOf(start))
    }

    @Test
    fun `tool_call_update completes with output`() {
        val bridge = SessionUpdateBridge("id")
        bridge.translate(AcpSessionUpdate(sessionUpdate = "tool_call", toolCallId = "t1", title = "Bash"))
        val chunks = bridge.translate(
            AcpSessionUpdate(
                sessionUpdate = "tool_call_update",
                toolCallId = "t1",
                status = "completed",
                rawOutput = JsonPrimitive("done"),
            )
        )
        assertEquals(1, chunks.size)
        val end = chunks[0] as StreamChunk.ServerToolEnd
        assertEquals(ServerToolStatus.COMPLETED, end.status)
        assertEquals(JsonPrimitive("done"), end.output)
    }

    @Test
    fun `failed tool call maps to FAILED status`() {
        val bridge = SessionUpdateBridge("id")
        bridge.translate(AcpSessionUpdate(sessionUpdate = "tool_call", toolCallId = "t2", title = "Bash"))
        val chunks = bridge.translate(
            AcpSessionUpdate(sessionUpdate = "tool_call_update", toolCallId = "t2", status = "failed")
        )
        val end = chunks[0] as StreamChunk.ServerToolEnd
        assertEquals(ServerToolStatus.FAILED, end.status)
    }

    @Test
    fun `partial tool update without status keeps card open`() {
        val bridge = SessionUpdateBridge("id")
        bridge.translate(AcpSessionUpdate(sessionUpdate = "tool_call", toolCallId = "t3", title = "Edit"))
        val chunks = bridge.translate(
            AcpSessionUpdate(sessionUpdate = "tool_call_update", toolCallId = "t3")
        )
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `finish closes unterminated streams and tool calls once`() {
        val bridge = SessionUpdateBridge("id")
        bridge.translate(v1Chunk("answer"))
        bridge.translate(AcpSessionUpdate(sessionUpdate = "agent_thought_chunk", content = AcpTextContent(text = "hmm")))
        bridge.translate(AcpSessionUpdate(sessionUpdate = "tool_call", toolCallId = "t9", title = "Search"))

        val first = bridge.finish()
        assertTrue(StreamChunk.TextEnd("id") in first)
        assertTrue(StreamChunk.ReasoningEnd(id = "id") in first)
        assertTrue(first.any { it is StreamChunk.ServerToolEnd && it.id == "t9" })

        // 二次 finish 无残留事件
        assertTrue(bridge.finish().isEmpty())
    }

    private fun statusOf(start: StreamChunk.ServerToolStart): ServerToolStatus =
        (start.metadata?.get("acp_status") as? JsonPrimitive)?.let {
            when (it.content) {
                "completed" -> ServerToolStatus.COMPLETED
                "failed" -> ServerToolStatus.FAILED
                else -> ServerToolStatus.IN_PROGRESS
            }
        } ?: ServerToolStatus.IN_PROGRESS

    @Test
    fun `extractFinalText reads session end summary`() {
        val update = AcpSessionUpdate(
            sessionUpdate = "session/end",
            delta = listOf(AcpContentChunk(type = "text", text = "final answer")),
        )
        assertEquals("final answer", extractFinalText(update))
        org.junit.Assert.assertNull(extractFinalText(v1Chunk("not an end")))
    }

    private fun plan(vararg items: Triple<String, String?, String?>) = AcpSessionUpdate(
        sessionUpdate = "plan",
        entries = items.map { (content, priority, status) ->
            AcpPlanEntry(content = content, priority = priority, status = status)
        },
    )

    @Test
    fun `plan update starts a fixed plan card with serialized entries`() {
        val bridge = SessionUpdateBridge("id")
        val chunks = bridge.translate(
            plan(
                Triple("read files", "high", "completed"),
                Triple("write tests", "medium", "in_progress"),
                Triple("ship it", null, "pending"),
            )
        )
        assertEquals(1, chunks.size)
        val start = chunks[0] as StreamChunk.ServerToolStart
        assertEquals(SessionUpdateBridge.PLAN_TOOL_ID, start.id)
        assertEquals("plan", start.toolName)

        val metadata = start.metadata!!
        assertEquals(JsonPrimitive("plan"), metadata["kind"])
        val entries = metadata["plan_entries"] as kotlinx.serialization.json.JsonArray
        assertEquals(3, entries.size)
        val first = entries[0] as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("read files"), first["content"])
        assertEquals(JsonPrimitive("completed"), first["status"])
    }

    @Test
    fun `repeated plan updates reuse the same card id`() {
        val bridge = SessionUpdateBridge("id")
        val first = bridge.translate(plan(Triple("a", null, "pending"))) as List<StreamChunk>
        val second = bridge.translate(plan(Triple("a", null, "completed"), Triple("b", null, "pending")))
        val firstStart = first[0] as StreamChunk.ServerToolStart
        val secondStart = second[0] as StreamChunk.ServerToolStart
        assertEquals(firstStart.id, secondStart.id)
        // 第二次快照携带最新条目数
        val entries = secondStart.metadata!!["plan_entries"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, entries.size)
    }

    @Test
    fun `empty plan updates produce nothing`() {
        val bridge = SessionUpdateBridge("id")
        assertTrue(
            bridge.translate(AcpSessionUpdate(sessionUpdate = "plan", entries = emptyList())).isEmpty()
        )
        assertTrue(bridge.translate(AcpSessionUpdate(sessionUpdate = "plan")).isEmpty())
    }

    @Test
    fun `current_mode_update streams a mode card`() {
        val bridge = SessionUpdateBridge("id")
        val chunks = bridge.translate(AcpSessionUpdate(sessionUpdate = "current_mode_update", currentModeId = "acceptEdits"))
        assertEquals(1, chunks.size)
        val start = chunks[0] as StreamChunk.ServerToolStart
        assertEquals(SessionUpdateBridge.MODE_TOOL_ID, start.id)
        assertEquals("acceptEdits", start.toolName)
        assertEquals(JsonPrimitive("mode"), start.metadata!!["kind"])

        // finish 闭合 mode 卡片
        assertTrue(bridge.finish().any { it is StreamChunk.ServerToolEnd && it.id == SessionUpdateBridge.MODE_TOOL_ID })
    }

    @Test
    fun `finish closes open plan card once`() {
        val bridge = SessionUpdateBridge("id")
        bridge.translate(plan(Triple("step", null, "pending")))
        val end = bridge.finish()
        assertTrue(end.any { it is StreamChunk.ServerToolEnd && it.id == SessionUpdateBridge.PLAN_TOOL_ID })
        assertTrue(bridge.finish().isEmpty())
    }
}
