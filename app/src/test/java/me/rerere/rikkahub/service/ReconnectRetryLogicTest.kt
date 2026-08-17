package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
