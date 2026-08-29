package me.rerere.rikkahub.data.cordis

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 能力缝（capability seams）注入测试：内核注册宿主能力、插件声明白名单访问、
 * 未声明能力拒绝（R7.4）。
 */
class CordisCapabilitiesTest {

    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    // 宿主提供的假实现
    private class FakeLlm : LlmSeam {
        val calls = mutableListOf<Int>()
        override suspend fun infer(config: JsonObject, messages: List<UIMessage>): LlmSeamResult {
            calls += messages.size
            return LlmSeamResult(
                output = messages,
                usage = buildJsonObject { put("promptTokens", 10) },
                provider = "fake",
                model = "fake-1",
            )
        }
    }

    private class FakeFs : FsSeam {
        override suspend fun read(path: String): String = "content:$path"
        override suspend fun write(path: String, content: String) = Unit
        override suspend fun exists(path: String): Boolean = true
        override suspend fun list(dir: String): List<String> = listOf("a.txt", "b.txt")
    }

    private fun host(
        llm: FakeLlm? = null,
        fs: FakeFs? = null,
    ) = CordisHost(
        llm = llm,
        fs = fs,
    )

    @Test
    fun `host capabilities are registered as root services`() = runBlocking {
        val llm = FakeLlm()
        val kernel = CordisKernel(host(llm = llm, fs = FakeFs()))
        val ctx = kernel.rootContext

        assertNotNull(ctx.get("llm"))
        assertNotNull(ctx.get("fs"))
        assertNull("未注入的能力缝不应注册", ctx.get("approval"))
    }

    @Test
    fun `plugin declared capabilities can access seam`() = runBlocking {
        val llm = FakeLlm()
        val kernel = CordisKernel(host(llm = llm))
        val captured = mutableListOf<LlmSeamResult>()

        kernel.register(
            CordisPlugin(
                id = "p1",
                capabilities = listOf("llm"),
                apply = {
                    val seam = seam("llm") as LlmSeam
                    val result = seam.infer(obj(), emptyList())
                    captured += result
                }
            )
        )

        assertEquals("fake-1", captured[0].model)
        assertEquals(1, llm.calls.size)
    }

    @Test
    fun `undeclared capability access is rejected`() = runBlocking {
        val llm = FakeLlm()
        val kernel = CordisKernel(host(llm = llm, fs = FakeFs()))

        try {
            kernel.register(
                CordisPlugin(
                    id = "p2",
                    capabilities = listOf("llm"), // 只声明 llm，未声明 fs
                    apply = {
                        seam("fs")
                    }
                )
            )
            fail("未声明的能力缝访问应抛出 CordisCapabilityNotDeclaredException")
        } catch (e: CordisPluginApplyException) {
            val cause = e.cause as CordisCapabilityNotDeclaredException
            assertTrue(cause.message!!.contains("fs"))
            assertTrue(cause.message!!.contains("p2"))
        }

        // 插件注册失败后应从 plugins 状态中移除
        assertFalse(kernel.pluginsState.value.contains("p2"))
    }

    @Test
    fun `plugin not declaring any capability cannot access seam`() = runBlocking {
        val llm = FakeLlm()
        val kernel = CordisKernel(host(llm = llm))

        try {
            kernel.register(
                CordisPlugin(
                    id = "p3",
                    apply = {
                        seam("llm")
                    }
                )
            )
            fail("无声明的插件访问能力缝应被拒绝")
        } catch (e: CordisPluginApplyException) {
            val cause = e.cause as CordisCapabilityNotDeclaredException
            assertTrue(cause.message!!.contains("llm"))
        }
    }

    @Test
    fun `plugin can still set and get own services without capabilities`() = runBlocking {
        val kernel = CordisKernel(host())

        kernel.register(
            CordisPlugin(
                id = "p4",
                apply = {
                    set("myService", object {})
                }
            )
        )

        assertNotNull(kernel.rootContext.get("myService"))
    }

    @Test
    fun `plugin with tools seam can register definitions and observe change`() = runBlocking {
        var changeCount = 0
        val tools = object : ToolsSeam {
            val defs = mutableListOf<ToolSeamDefinition>()
            override fun register(tool: ToolSeamDefinition): Boolean {
                if (defs.any { it.name == tool.name }) return false
                defs += tool
                return true
            }

            override fun unregister(name: String): Boolean = defs.removeAll { it.name == name }

            override fun definitions(): List<ToolSeamDefinition> = defs.toList()

            override fun get(name: String): ToolSeamDefinition? = defs.firstOrNull { it.name == name }

            override fun notifyChanged() {
                changeCount++
            }
        }

        val kernel = CordisKernel(CordisHost(tools = tools))
        kernel.register(
            CordisPlugin(
                id = "p5",
                capabilities = listOf("tools"),
                apply = {
                    val seam = seam("tools") as ToolsSeam
                    seam.register(
                        ToolSeamDefinition(
                            name = "echo",
                            description = "echo input",
                            schema = null,
                            execute = { input -> input },
                        )
                    )
                    seam.notifyChanged()
                }
            )
        )

        assertNotNull(tools.get("echo"))
        assertEquals(1, changeCount)
        assertTrue(tools.definitions().any { it.name == "echo" })
    }
}