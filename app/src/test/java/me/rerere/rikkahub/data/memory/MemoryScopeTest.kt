package me.rerere.rikkahub.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryScopeTest {

    @Test
    fun `global memory assistants share the global id`() {
        assertEquals("__global__", MemoryScope.memoryAssistantId(useGlobalMemory = true, assistantId = "asst-1"))
    }

    @Test
    fun `assistant memory stays scoped to the assistant`() {
        assertEquals("asst-1", MemoryScope.memoryAssistantId(useGlobalMemory = false, assistantId = "asst-1"))
    }

    @Test
    fun `durable targets are persisted`() {
        assertTrue(MemoryScope.isDurable("USER"))
        assertTrue(MemoryScope.isDurable("MEMORY"))
        assertTrue(MemoryScope.isDurable("PROJECT"))
        assertTrue(MemoryScope.isDurable("OPS"))
    }

    @Test
    fun `general target is session local`() {
        assertFalse(MemoryScope.isDurable("GENERAL"))
    }

    @Test
    fun `scope keys are stable constants`() {
        assertEquals("durable", MemoryScope.DURABLE)
        assertEquals("local", MemoryScope.LOCAL)
    }
}
