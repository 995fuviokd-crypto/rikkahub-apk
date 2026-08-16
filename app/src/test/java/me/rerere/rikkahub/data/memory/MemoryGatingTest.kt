package me.rerere.rikkahub.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGatingTest {

    @Test
    fun `trivial greeting should be recognized`() {
        assertTrue(MemoryGating.isTrivial("好的"))
        assertTrue(MemoryGating.isTrivial("ok"))
        assertTrue(MemoryGating.isTrivial("收到"))
        assertTrue(MemoryGating.isTrivial("Thanks"))
        assertTrue(MemoryGating.isTrivial(null))
    }

    @Test
    fun `meaningful query should not be trivial`() {
        assertFalse(MemoryGating.isTrivial("帮我写一个排序算法"))
        assertFalse(MemoryGating.isTrivial("what is the current memory limit"))
    }

    @Test
    fun `short or empty queries should skip retrieval`() {
        assertTrue(MemoryGating.shouldSkipRetrieval("", 2))
        assertTrue(MemoryGating.shouldSkipRetrieval("hi", 2))
        assertTrue(MemoryGating.shouldSkipRetrieval("好的", 2))
        assertFalse(MemoryGating.shouldSkipRetrieval("如何优化记忆", 2))
    }

    @Test
    fun `dedup key normalizes whitespace and case`() {
        assertEquals("hello world", MemoryGating.dedupKey("  Hello   World  "))
        assertEquals("你好 世界", MemoryGating.dedupKey("你好 世界"))
    }

    @Test
    fun `cjk query produces deterministic segments`() {
        val tokens = MemoryGating.queryTokens("当前运行的系统")
        assertTrue(tokens.isNotEmpty())
        val second = MemoryGating.queryTokens("当前运行的系统")
        assertEquals(tokens, second)
    }

    @Test
    fun `semantic tokens drop stopwords`() {
        val tokens = MemoryGating.semanticQueryTokens("告诉我当前的系统")
        assertFalse(tokens.contains("告诉我"))
        assertFalse(tokens.contains("当前"))
        assertTrue(tokens.contains("系统"))
    }

    @Test
    fun `current state queries detected`() {
        assertTrue(MemoryGating.queriesCurrentState("当前系统是什么"))
        assertTrue(MemoryGating.queriesCurrentState("现在的内存限制是多少"))
        assertFalse(MemoryGating.queriesCurrentState("你之前是怎么处理的"))
    }

    @Test
    fun `retrieval query tokens combine semantic and intent terms`() {
        val tokens = MemoryGating.retrievalQueryTokens("当前在哪个目录运行")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.contains("目录") || tokens.contains("位置") || tokens.contains("路径"))
    }

    @Test
    fun `stemming keeps related forms consistent`() {
        assertEquals(MemoryGating.stemToken("running"), MemoryGating.stemToken("run"))
        assertEquals(MemoryGating.stemToken("memories"), MemoryGating.stemToken("memory"))
    }

    @Test
    fun `fts query escapes special characters`() {
        val query = MemoryGating.buildFtsQuery(listOf("a\"b", "系统"))
        assertFalse(query.contains("a\"b"))
        assertTrue(query.contains("系统"))
    }
}
