package me.rerere.rikkahub.data.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHashEmbedderTest {

    private val embedder = LocalHashEmbedder()

    @Test
    fun `embedding is deterministic`() {
        val a = embedder.embed("记忆系统闭环测试")
        val b = embedder.embed("记忆系统闭环测试")
        assertArrayEquals(a, b, 0.0001f)
    }

    @Test
    fun `embedding dimension is fixed`() {
        assertEquals(256, embedder.embed("任意文本").size)
    }

    @Test
    fun `identical text similarity is one`() {
        val vector = embedder.embed("当前系统")
        assertEquals(1.0f, embedder.similarity(vector, vector), 0.0001f)
    }

    @Test
    fun `similar text scores higher than unrelated text`() {
        val query = embedder.embed("记忆系统")
        val related = embedder.embed("记忆系统由 MemoryOps 管理")
        val unrelated = embedder.embed("今天天气很好适合出门")
        assertTrue(embedder.similarity(query, related) > embedder.similarity(query, unrelated))
    }

    @Test
    fun `empty text does not crash`() {
        assertEquals(256, embedder.embed("").size)
    }
}
