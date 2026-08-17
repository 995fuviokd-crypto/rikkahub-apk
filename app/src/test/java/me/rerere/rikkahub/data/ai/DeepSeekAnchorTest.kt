package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekAnchorTest {

    @Test
    fun `deepseek models are detected by model id`() {
        assertTrue(DeepSeekAnchor.isDeepSeekModel("deepseek-v4-pro-0813"))
        assertTrue(DeepSeekAnchor.isDeepSeekModel("deepseek-v4-flash-0713"))
        assertTrue(DeepSeekAnchor.isDeepSeekModel("DeepSeek-V4-Pro"))
        assertTrue(DeepSeekAnchor.isDeepSeekModel("deep-seek-r1"))
        assertTrue(DeepSeekAnchor.isDeepSeekModel("ds-chat"))
        assertTrue(DeepSeekAnchor.isDeepSeekModel("ds/v4"))
        assertTrue(DeepSeekAnchor.isDeepSeekModel("gpt-oss-20b-seek"))
        assertFalse(DeepSeekAnchor.isDeepSeekModel("gpt-4o"))
        assertFalse(DeepSeekAnchor.isDeepSeekModel("claude-sonnet-4"))
        assertFalse(DeepSeekAnchor.isDeepSeekModel(""))
        assertFalse(DeepSeekAnchor.isDeepSeekModel(null))
    }

    @Test
    fun `cap schedule is 1024 then 4096 then released`() {
        assertEquals(1024, DeepSeekAnchor.capFor(1))
        assertEquals(4096, DeepSeekAnchor.capFor(2))
        assertNull(DeepSeekAnchor.capFor(3))
        assertNull(DeepSeekAnchor.capFor(4))
        assertNull(DeepSeekAnchor.capFor(0))
    }

    @Test
    fun `tone is classified by first reasoning block`() {
        assertEquals(DeepSeekAnchor.Tone.WE_NEED, DeepSeekAnchor.toneOf("We need answer the user."))
        assertEquals(DeepSeekAnchor.Tone.LETS, DeepSeekAnchor.toneOf("Let's inspect the file."))
        assertEquals(DeepSeekAnchor.Tone.LET_ME, DeepSeekAnchor.toneOf("Let me read the code."))
        assertEquals(DeepSeekAnchor.Tone.I, DeepSeekAnchor.toneOf("I can fix this."))
        assertEquals(DeepSeekAnchor.Tone.THE_USER, DeepSeekAnchor.toneOf("The user wants to fix a bug."))
        assertEquals(DeepSeekAnchor.Tone.OTHER, DeepSeekAnchor.toneOf("  \n"))
        assertEquals(DeepSeekAnchor.Tone.OTHER, DeepSeekAnchor.toneOf(null))
    }

    @Test
    fun `collaborative tone is we need or lets`() {
        assertTrue(DeepSeekAnchor.isCollaborative("We need prepare."))
        assertTrue(DeepSeekAnchor.isCollaborative("Let's act."))
        assertFalse(DeepSeekAnchor.isCollaborative("Let me dig in."))
        assertFalse(DeepSeekAnchor.isCollaborative("The user asked."))
    }

    @Test
    fun `degraded tone is let me or the user`() {
        assertTrue(DeepSeekAnchor.isDegraded("Let me explore."))
        assertTrue(DeepSeekAnchor.isDegraded("The user wants."))
        assertFalse(DeepSeekAnchor.isDegraded("We need plan."))
        assertFalse(DeepSeekAnchor.isDegraded("Let's implement."))
    }

    @Test
    fun `warmup anchor injects warmup user and replay before first user`() {
        val messages = listOf(
            UIMessage.system("You are a helpful assistant."),
            UIMessage.user("Fix this bug"),
        )
        val anchored = DeepSeekAnchor.applyWarmupAnchor(messages)

        assertEquals(4, anchored.size)
        assertEquals(MessageRole.SYSTEM, anchored[0].role)
        assertEquals(MessageRole.USER, anchored[1].role)
        assertEquals(MessageRole.ASSISTANT, anchored[2].role)
        assertEquals(MessageRole.USER, anchored[3].role)
        assertEquals(DeepSeekAnchor.WARMUP_MESSAGE, anchored[1].toText())
        assertTrue(anchored[2].hasPart<UIMessagePart.Reasoning>())
        assertEquals("Fix this bug", anchored[3].toText())
    }

    @Test
    fun `warmup anchor is no-op without user message`() {
        val messages = listOf(UIMessage.system("sys"))
        assertEquals(messages, DeepSeekAnchor.applyWarmupAnchor(messages))
    }

    @Test
    fun `anchor prefix is prepended to existing system message`() {
        val messages = listOf(UIMessage.system("original"))
        val result = DeepSeekAnchor.applyAnchorPrefix(messages)

        assertEquals(1, result.size)
        val text = result[0].toText()
        assertTrue(text.startsWith(DeepSeekAnchor.TONE_DIRECTIVE))
        assertTrue(text.contains(DeepSeekAnchor.BEYOND_PROMPT))
        assertTrue(text.contains("original"))
    }

    @Test
    fun `anchor prefix creates system message when absent`() {
        val messages = listOf(UIMessage.user("hi"))
        val result = DeepSeekAnchor.applyAnchorPrefix(messages)

        assertEquals(2, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals(DeepSeekAnchor.ANCHOR_PREFIX, result[0].toText())
    }
}
