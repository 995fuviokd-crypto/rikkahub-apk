package me.rerere.rikkahub.data.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CbsMacroEngineTest {

    @Test
    fun `removeComments strips comment macros`() {
        val input = "Hello{{// this is a comment }} World"
        assertEquals("Hello World", CbsMacroEngine.resolve(input))
    }

    @Test
    fun `resolveRandom picks one option`() {
        val input = "Value is {{random:alpha|beta|gamma}}"
        val result = CbsMacroEngine.resolve(input)
        val suffix = result.removePrefix("Value is ")
        assertTrue(suffix in listOf("alpha", "beta", "gamma"))
    }

    @Test
    fun `resolveRoll generates number in range`() {
        val input = "{{roll:100}}"
        val result = CbsMacroEngine.resolve(input)
        val number = result.toIntOrNull()
        assertTrue(number != null && number in 1..100)
    }

    @Test
    fun `resolvePick selects from comma separated`() {
        val input = "{{pick:apple,banana,cherry}}"
        val result = CbsMacroEngine.resolve(input)
        assertTrue(result in listOf("apple", "banana", "cherry"))
    }

    @Test
    fun `resolveReverse reverses text`() {
        val input = "{{reverse:hello}}"
        assertEquals("olleh", CbsMacroEngine.resolve(input))
    }

    @Test
    fun `combined macros resolve in order`() {
        val input = "A{{//cmt}}B {{roll:10}} C {{reverse:xyz}}"
        val result = CbsMacroEngine.resolve(input)
        assertTrue(result.startsWith("AB"))
        assertTrue(result.contains("C"))
        assertTrue(result.endsWith("zyx"))
    }

    @Test
    fun `no macros returns original text`() {
        val input = "Hello World no macros here"
        assertEquals(input, CbsMacroEngine.resolve(input))
    }

    @Test
    fun `empty text returns empty`() {
        assertEquals("", CbsMacroEngine.resolve(""))
    }

    @Test
    fun `invalid roll preserves original`() {
        val input = "{{roll:abc}}"
        assertEquals("{{roll:abc}}", CbsMacroEngine.resolve(input))
    }

    @Test
    fun `random with single option returns that option`() {
        val input = "{{random:only}}"
        assertEquals("only", CbsMacroEngine.resolve(input))
    }
}
