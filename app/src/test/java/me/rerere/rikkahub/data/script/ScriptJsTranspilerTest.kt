package me.rerere.rikkahub.data.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptJsTranspilerTest {

    @Test
    fun `transpile returns source unchanged for async function`() {
        val src = """
            async function foo(a) {
                var r = await Tools.Chat.listChats({ limit: a });
                return r;
            }
        """.trimIndent()
        val out = ScriptJsTranspiler.transpile(src)
        assertEquals(src, out)
    }

    @Test
    fun `transpile returns source unchanged for async arrow`() {
        val src = """
            var f = async (x) => {
                var v = await Tools.Files.read(x);
                return v;
            };
        """.trimIndent()
        val out = ScriptJsTranspiler.transpile(src)
        assertEquals(src, out)
    }

    @Test
    fun `transpile returns source unchanged for nested async`() {
        val src = """
            async function outer() {
                var v = await inner();
                return v;
            }
            async function inner() {
                return await Tools.Files.read("/a");
            }
        """.trimIndent()
        val out = ScriptJsTranspiler.transpile(src)
        assertEquals(src, out)
    }

    @Test
    fun `transpile returns source unchanged for sync code`() {
        val src = "function add(a, b) { return a + b; }"
        val out = ScriptJsTranspiler.transpile(src)
        assertEquals(src, out)
    }

    @Test
    fun `transpile preserves strings templates and comments`() {
        val src = """
            async function f() {
                // await Tools.Chat.listChats() should stay in comment
                var s1 = "await not converted";
                var s2 = 'await not converted';
                var t = `await ${'$'}{x} still string`;
                var r = await Tools.System.sendNotification("hi");
                return r;
            }
        """.trimIndent()
        val out = ScriptJsTranspiler.transpile(src)
        assertEquals(src, out)
    }

    @Test
    fun `transpile preserves regex literals`() {
        val src = """
            var re = /await\\w+/g;
            var n = 3 / 2;
        """.trimIndent()
        val out = ScriptJsTranspiler.transpile(src)
        assertEquals(src, out)
    }

    @Test
    fun `run gen runtime is empty`() {
        // V8 natively supports async/await; no generator shim needed
        assertEquals("", ScriptJsTranspiler.RUN_GEN_RUNTIME)
    }
}
