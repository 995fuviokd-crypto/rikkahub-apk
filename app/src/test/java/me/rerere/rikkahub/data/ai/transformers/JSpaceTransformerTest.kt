package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JSpaceTransformerTest {

    private fun toolCallMessage(): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Text("Let me check the file."),
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "workspace_shell",
                input = """{"command":"ls"}""",
            ),
        ),
    )

    @Test
    fun `should inject jspace guide after the last user message`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("帮我分析这个项目"),
            toolCallMessage(),
        )
        val result = transformJSpace(messages)
        val injected = result.filter {
            it.role == MessageRole.USER && it.toText().contains("[jspace-cognition]")
        }
        assertEquals(1, injected.size)
    }

    @Test
    fun `should not inject without any user message`() {
        val messages = listOf(
            toolCallMessage(),
        )
        val result = transformJSpace(messages)
        assertEquals(messages, result)
    }

    @Test
    fun `injection should not break user to assistant-with-tools adjacency`() {
        val messages = listOf(
            UIMessage.user("读取并分析这个项目"),
            toolCallMessage(),
        )
        val result = transformJSpace(messages)
        val guideIndex = result.indexOfFirst {
            it.role == MessageRole.USER && it.toText().contains("[jspace-cognition]")
        }
        assertTrue(guideIndex >= 0)
        val previous = result.getOrNull(guideIndex - 1)
        val next = result.getOrNull(guideIndex + 1)
        // 引导不得夹在 USER 与带工具的 ASSISTANT 之间
        val sandwiched = previous?.role == MessageRole.USER &&
            next?.role == MessageRole.ASSISTANT &&
            next.getTools().isNotEmpty()
        assertEquals(false, sandwiched)
    }

    @Test
    fun `guide should cover core jspace mechanisms`() {
        assertTrue(JSPACE_GUIDE.contains("[jspace-cognition]"))
        assertTrue(JSPACE_GUIDE.contains("inner"))
        assertTrue(JSPACE_GUIDE.contains("ledger"))
        assertTrue(JSPACE_GUIDE.contains("outer"))
        assertTrue(JSPACE_GUIDE.contains("✓"))
        assertTrue(JSPACE_GUIDE.contains("We need"))
        assertTrue(JSPACE_GUIDE.contains("finite candidate set"))
    }
}
