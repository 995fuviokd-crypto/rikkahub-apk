package me.rerere.rikkahub.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryScoringTest {

    @Test
    fun `bm25 lower value maps to higher score`() {
        val result = MemoryScoring.bm25ToScore(mapOf<Int, Float?>(1 to 3.0f, 2 to 1.0f, 3 to 2.0f))
        assertEquals(1.0f, result[2]!!, 0.0001f) // 最小 bm25 -> 1.0
        assertEquals(0.0f, result[1]!!, 0.0001f) // 最大 bm25 -> 0.0
    }

    @Test
    fun `bm25 equal values all get one`() {
        val result = MemoryScoring.bm25ToScore(mapOf<Int, Float?>(1 to 2.0f, 2 to 2.0f))
        assertEquals(1.0f, result[1]!!, 0.0001f)
        assertEquals(1.0f, result[2]!!, 0.0001f)
    }

    @Test
    fun `combine scores is a bounded linear combination`() {
        val score = MemoryScoring.combineScores(
            lexical = 0.8f,
            vector = 0.5f,
            bm25 = 0.2f,
            lexicalWeight = 0.45f,
            vectorWeight = 0.40f,
            bm25Weight = 0.15f,
        )
        assertTrue(score in 0.0f..1.0f)
        assertEquals(0.8f * 0.45f + 0.5f * 0.40f + 0.2f * 0.15f, score, 0.0001f)
    }

    @Test
    fun `relevant content scores higher than irrelevant`() {
        val high = MemoryScoring.lexicalScore("当前记忆系统", "记忆系统由 MemoryOps 管理", "记忆说明", "tool", "MEMORY")
        val low = MemoryScoring.lexicalScore("当前记忆系统", "天气晴朗适合出游", null, "tool", "MEMORY")
        assertTrue(high > low)
    }

    @Test
    fun `phrase match adds bonus`() {
        val phrase = MemoryScoring.lexicalScore("帮我排序", "帮我排序这些数字", null, "tool", "MEMORY")
        val noPhrase = MemoryScoring.lexicalScore("帮我排序", "排序是很常见的需求", null, "tool", "MEMORY")
        assertTrue(phrase > noPhrase)
    }

    @Test
    fun `rrf promotes ids present in multiple signals`() {
        val lexical = listOf(1, 2, 3)
        val vector = listOf(2, 3, 4)
        val fused = MemoryScoring.reciprocalRankFusion(
            rankedLists = mapOf("lexical" to lexical, "vector" to vector),
            weights = mapOf("lexical" to 1.0f, "vector" to 1.0f),
            k = 60,
            minSignals = 1,
        ).toMap()
        // 同时出现在两个信号中的 id 2 应高于只出现一次的 id 1/4
        assertTrue(fused.getValue(2) > fused.getValue(1))
        assertTrue(fused.getValue(2) > fused.getValue(4))
    }

    @Test
    fun `semantic similarity of identical text is high`() {
        val score = MemoryScoring.semanticSimilarity("记忆系统", "记忆系统")
        assertTrue(score > 0.9f)
    }
}
