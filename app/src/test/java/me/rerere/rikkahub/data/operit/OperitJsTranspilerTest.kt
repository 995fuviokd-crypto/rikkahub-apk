package me.rerere.rikkahub.data.operit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperitJsTranspilerTest {

    @Test
    fun `async function declaration is converted to generator wrapper`() {
        val src = """
            async function foo(a) {
                var r = await Tools.Chat.listChats({ limit: a });
                return r;
            }
        """.trimIndent()
        val out = OperitJsTranspiler.transpile(src)
        assertTrue(out.contains("function foo(a) { return __operitRunGen(function*()"))
        assertTrue(out.contains("var r = yield Tools.Chat.listChats({ limit: a });"))
        assertTrue(out.contains("return r;"))
        assertFalse(out.contains("async function"))
        assertFalse(out.contains("await"))
    }

    @Test
    fun `async arrow with block body is converted`() {
        val src = """
            var f = async (x) => {
                var v = await Tools.Files.read(x);
                return v;
            };
        """.trimIndent()
        val out = OperitJsTranspiler.transpile(src)
        assertTrue(out.contains("(x) => { return __operitRunGen(function*()"))
        assertTrue(out.contains("var v = yield Tools.Files.read(x);"))
        assertFalse(out.contains("async"))
        assertFalse(out.contains("await"))
    }

    @Test
    fun `async arrow with expression body is converted`() {
        val src = """
            var f = async x => x * 2;
        """.trimIndent()
        val out = OperitJsTranspiler.transpile(src)
        assertTrue(out.contains("x => __operitRunGen(function*() { return x * 2; })"))
    }

    @Test
    fun `async object method is converted with this binding`() {
        val src = """
            var obj = {
                async getInfo() {
                    var r = await Tools.Chat.getMessages("id", {});
                    return r;
                }
            };
        """.trimIndent()
        val out = OperitJsTranspiler.transpile(src)
        assertTrue(out.contains("getInfo() { return __operitRunGen(function*()"))
        assertTrue(out.contains("var r = yield Tools.Chat.getMessages"))
    }

    @Test
    fun `nested async functions are converted`() {
        val src = """
            async function outer() {
                var v = await inner();
                return v;
            }
            async function inner() {
                return await Tools.Files.read("/a");
            }
        """.trimIndent()
        val out = OperitJsTranspiler.transpile(src)
        assertTrue(out.contains("function outer() { return __operitRunGen(function*()"))
        assertTrue(out.contains("function inner() { return __operitRunGen(function*()"))
        assertTrue(out.contains("return yield Tools.Files.read"))
    }

    @Test
    fun `await inside strings templates and comments is untouched`() {
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
        val out = OperitJsTranspiler.transpile(src)
        assertTrue(out.contains("await not converted"))
        assertTrue(out.contains("await ${'$'}{x} still string"))
        assertTrue(out.contains("// await Tools.Chat.listChats() should stay in comment"))
        assertEquals(1, Regex("yield Tools\\.System").findAll(out).count())
    }

    @Test
    fun `regex literal is preserved`() {
        val src = """
            var re = /await\\w+/g;
            var n = 3 / 2;
        """.trimIndent()
        val out = OperitJsTranspiler.transpile(src)
        assertTrue(out.contains("var re = /await\\\\w+/g;"))
        assertTrue(out.contains("var n = 3 / 2;"))
    }

    @Test
    fun `run gen runtime is injectable`() {
        assertTrue(OperitJsTranspiler.RUN_GEN_RUNTIME.contains("function __operitRunGen"))
        assertTrue(OperitJsTranspiler.RUN_GEN_RUNTIME.contains("g.next()"))
    }

    @Test
    fun `run gen runtime invokes generator correctly without self`() {
        // 无 self 时必须调用 gen() 而非把函数对象当生成器实例，否则 g.next 崩溃
        val runtime = OperitJsTranspiler.RUN_GEN_RUNTIME
        assertTrue(runtime.contains("gen()"))
        assertTrue(runtime.contains("gen.call(self)"))
    }

    @Test
    fun `no async functions leaves source mostly unchanged`() {
        val src = "function add(a, b) { return a + b; }"
        val out = OperitJsTranspiler.transpile(src)
        assertEquals(src, out.trim())
    }

    @Test
    fun `brace balance is preserved after conversion`() {
        val src = """
            async function f(a) {
                if (a > 0) {
                    return { ok: true, data: await Tools.Files.read("x") };
                } else {
                    return null;
                }
            }
        """.trimIndent()
        val out = OperitJsTranspiler.transpile(src)
        // 转换后 generator body 新增一对花括号（function*() { ... }），输出自身仍须平衡
        assertEquals(countChar(out, '{'), countChar(out, '}'))
        assertEquals(countChar(src, '{') + 1, countChar(out, '{'))
        assertEquals(countChar(src, '}') + 1, countChar(out, '}'))
        assertTrue(out.contains("yield Tools.Files.read"))
    }

    @Test
    fun `multiple statements with await inside loop`() {
        val src = """
            async function collect(ids) {
                var out = [];
                for (var i = 0; i < ids.length; i++) {
                    var m = await Tools.Chat.getMessages(ids[i], { limit: 10 });
                    if (m && m.messages) out = out.concat(m.messages);
                }
                return out;
            }
        """.trimIndent()
        val out = OperitJsTranspiler.transpile(src)
        assertTrue(out.contains("yield Tools.Chat.getMessages(ids[i]"))
        assertTrue(out.contains("out = out.concat(m.messages);"))
        assertTrue(out.contains("function collect(ids) { return __operitRunGen(function*()"))
    }

    @Test
    fun dumpMissPulseTranspileForNodeCheck() {
        val src = java.io.File("/tmp/opencode/rh_miss_pulse.js").readText()
        val out = OperitJsTranspiler.transpile(src)
        java.io.File("/tmp/opencode/t_miss_kotlin.js").writeText(OperitJsTranspiler.RUN_GEN_RUNTIME + "\n" + out)
        java.io.File("/tmp/opencode/t_miss_kotlin_len.txt").writeText(out.length.toString())
    }

    private fun countChar(s: String, c: Char): Int = s.count { it == c }
}
