package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnchorBudgetLadderTest {

    @Test
    fun `first round uses the base budget`() {
        assertEquals(1024, AnchorBudgetLadder.budgetFor(userRound = 1, maxTokens = null))
    }

    @Test
    fun `budget climbs step by step within warmup rounds`() {
        assertEquals(1024, AnchorBudgetLadder.budgetFor(userRound = 1, maxTokens = null))
        assertEquals(1536, AnchorBudgetLadder.budgetFor(userRound = 2, maxTokens = null))
        assertEquals(2048, AnchorBudgetLadder.budgetFor(userRound = 3, maxTokens = null))
        assertEquals(2560, AnchorBudgetLadder.budgetFor(userRound = 4, maxTokens = null))
    }

    @Test
    fun `budget is released after warmup rounds`() {
        assertEquals(8000, AnchorBudgetLadder.budgetFor(userRound = 5, maxTokens = 8000))
    }

    @Test
    fun `no user limit means unlimited after warmup`() {
        assertNull(AnchorBudgetLadder.budgetFor(userRound = 5, maxTokens = null))
    }

    @Test
    fun `user limit caps the ladder`() {
        assertEquals(512, AnchorBudgetLadder.budgetFor(userRound = 1, maxTokens = 512))
        assertEquals(800, AnchorBudgetLadder.budgetFor(userRound = 1, maxTokens = 800))
    }

    @Test
    fun `non positive round falls back to user limit`() {
        assertNull(AnchorBudgetLadder.budgetFor(userRound = 0, maxTokens = null))
        assertEquals(2048, AnchorBudgetLadder.budgetFor(userRound = -1, maxTokens = 2048))
    }

    @Test
    fun `custom ladder parameters are respected`() {
        assertEquals(500, AnchorBudgetLadder.budgetFor(userRound = 1, maxTokens = null, base = 500, step = 100, warmupRounds = 3))
        assertEquals(600, AnchorBudgetLadder.budgetFor(userRound = 2, maxTokens = null, base = 500, step = 100, warmupRounds = 3))
        assertEquals(700, AnchorBudgetLadder.budgetFor(userRound = 3, maxTokens = null, base = 500, step = 100, warmupRounds = 3))
        assertEquals(999, AnchorBudgetLadder.budgetFor(userRound = 4, maxTokens = 999, base = 500, step = 100, warmupRounds = 3))
    }
}
