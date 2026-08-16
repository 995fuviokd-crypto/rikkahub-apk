package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectRetryLogicTest {

    @Test
    fun `io exception should be retriable`() {
        assertTrue(isRetriableNetworkError(java.io.IOException("connection reset")))
        assertTrue(isRetriableNetworkError(RuntimeException("wrap", java.io.IOException("timeout"))))
    }

    @Test
    fun `http exceptions with retriable status codes should be retriable`() {
        for (code in listOf(429, 500, 502, 503, 504, 501, 400, 401, 403, 404, 405, 409)) {
            assertTrue("code $code should be retriable", isRetriableNetworkError(HttpException("err $code", code)))
        }
    }

    @Test
    fun `http status code in nested cause chain should be retriable`() {
        val deep = RuntimeException("outer", RuntimeException("mid", HttpException("gateway error", 502)))
        assertTrue(isRetriableNetworkError(deep))
    }

    @Test
    fun `status code embedded in message text should be retriable`() {
        // 协议回退等异常未携带 statusCode，仅消息含 "HTTP 503"
        assertTrue(isRetriableNetworkError(RuntimeException("Failed to get response: HTTP 503 Service Unavailable")))
        assertTrue(isRetriableNetworkError(RuntimeException("upstream returned code=429")))
    }

    @Test
    fun `cancellation should never be retriable`() {
        assertFalse(isRetriableNetworkError(CancellationException("cancelled")))
        assertFalse(isRetriableNetworkError(RuntimeException("wrap", CancellationException("x"))))
    }

    @Test
    fun `unrelated exceptions should not be retriable`() {
        assertFalse(isRetriableNetworkError(IllegalStateException("bad state")))
        assertFalse(isRetriableNetworkError(null))
        // 400 类参数不兼容文本但无状态码特征，不触发重连
        assertFalse(isRetriableNetworkError(RuntimeException("no status code here")))
    }

    @Test
    fun `extractHttpStatusCode prefers structured field over text`() {
        assertEquals(429, extractHttpStatusCode(HttpException("some message", 429)))
        assertEquals(503, extractHttpStatusCode(RuntimeException("HTTP 503 Server Error")))
        assertEquals(429, extractHttpStatusCode(RuntimeException("code=429 too many requests")))
        assertNull(extractHttpStatusCode(RuntimeException("no status")))
        assertNull(extractHttpStatusCode(IllegalStateException("boom")))
    }

    @Test
    fun `retriable set contains all user reported codes`() {
        for (code in listOf(503, 400, 500, 429, 502, 501, 401, 403, 404, 405, 409, 504)) {
            assertTrue("$code in retriable set", code in RETRIABLE_HTTP_STATUS_CODES)
        }
    }
}
