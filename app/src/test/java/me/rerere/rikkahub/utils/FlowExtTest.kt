package me.rerere.rikkahub.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowExtTest {

    private fun fastStream(count: Int): Flow<Int> = flow {
        repeat(count) { emit(it) }
    }

    @Test
    fun `high frequency stream is coalesced and keeps latest`() = runBlocking {
        val emitted = fastStream(200).throttleLatest(20).toList()
        assertTrue("should coalesce many frames: ${emitted.size}", emitted.size < 200)
        assertEquals("latest value must be preserved", 199, emitted.last())
    }

    @Test
    fun `sparse stream passes values through`() = runBlocking {
        val emitted = flow {
            emit(1)
            delay(30)
            emit(2)
        }.throttleLatest(10).toList()
        assertEquals(listOf(1, 2), emitted)
    }

    @Test
    fun `empty flow completes immediately`() = runBlocking {
        val emitted = withTimeout(1000) {
            flow<Int> {}.throttleLatest(10).toList()
        }
        assertTrue(emitted.isEmpty())
    }
}
