package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

class ReconnectRetryLogicTest {

    @Test
    fun `any non-cancellation error should reconnect`() {
        assertTrue(shouldReconnect(java.io.IOException("connection reset")))
        assertTrue(shouldReconnect(RuntimeException("wrap", java.io.IOException("timeout"))))
        assertTrue(shouldReconnect(IllegalStateException("bad state")))
        assertTrue(shouldReconnect(RuntimeException("Failed to get response: HTTP 503 Service Unavailable")))
        assertTrue(shouldReconnect(RuntimeException("no status code here")))
    }

    @Test
    fun `cancellation should never reconnect`() {
        assertFalse(shouldReconnect(CancellationException("cancelled")))
        assertFalse(shouldReconnect(RuntimeException("wrap", CancellationException("x"))))
        val deep = RuntimeException("outer", RuntimeException("mid", CancellationException("deep")))
        assertFalse(shouldReconnect(deep))
    }

    @Test
    fun `null error should not reconnect`() {
        assertFalse(shouldReconnect(null))
    }

    @Test
    fun `incomplete assistant message should be rolled back`() {
        val half = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("partial")),
            finishedAt = null,
        )
        assertTrue(shouldRollbackIncompleteAssistantMessage(listOf(half)))
        assertTrue(shouldRollbackIncompleteAssistantMessage(listOf(UIMessage.user("q"), half)))
    }

    @Test
    fun `completed assistant message should not be rolled back`() {
        val done = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("full")),
            finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
        )
        assertFalse(shouldRollbackIncompleteAssistantMessage(listOf(UIMessage.user("q"), done)))
    }

    @Test
    fun `user message should not be rolled back`() {
        assertFalse(shouldRollbackIncompleteAssistantMessage(listOf(UIMessage.user("hello"))))
    }

    @Test
    fun `empty messages should not be rolled back`() {
        assertFalse(shouldRollbackIncompleteAssistantMessage(emptyList()))
    }
}
