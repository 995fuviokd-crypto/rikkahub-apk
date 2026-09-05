package me.rerere.rikkahub.data.cordis

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2.2：tools 缝注册表 + `tools/change` 事件驱动派发。
 * HostToolsSeam 构造不传 scope → notifyChanged 退回同步 emit（测试确定性路径）。
 */
class HostToolsSeamTest {

    private fun tool(name: String) = ToolSeamDefinition(
        name = name,
        description = "test tool $name",
        execute = { kotlinx.serialization.json.JsonPrimitive("ok") },
    )

    @Test
    fun `register emits tools change event`() = runBlocking {
        val eventBus = CordisEventBus()
        var received: CordisEvent? = null
        eventBus.on("tools/change") { event ->
            received = event
            null
        }
        val seam = HostToolsSeam(eventBus)

        assertTrue(seam.register(tool("a.hello")))

        val e = received
        assertTrue("tools/change should be dispatched", e != null)
        assertEquals("a.hello", e!!.payload["tools"]?.jsonPrimitive?.content)
        assertEquals("1", e.payload["count"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unregister emits tools change event`() = runBlocking {
        val eventBus = CordisEventBus()
        val seam = HostToolsSeam(eventBus)
        seam.register(tool("a.hello"))

        var received: CordisEvent? = null
        eventBus.on("tools/change") { event ->
            received = event
            null
        }

        assertTrue(seam.unregister("a.hello"))
        assertTrue("no tools left", received!!.payload["tools"]?.jsonPrimitive?.content.isNullOrEmpty())
    }

    @Test
    fun `duplicate name rejected without event`() = runBlocking {
        val eventBus = CordisEventBus()
        var dispatchCount = 0
        eventBus.on("tools/change") { _ ->
            dispatchCount++
            null
        }
        val seam = HostToolsSeam(eventBus)

        assertTrue(seam.register(tool("dup")))
        assertTrue("duplicate register should fail", !seam.register(tool("dup")))
        assertEquals("only one tools/change for one effective registration", 1, dispatchCount)
    }

    @Test
    fun `definitions and get reflect registry state`() = runBlocking {
        val seam = HostToolsSeam(CordisEventBus())
        seam.register(tool("p1.alpha"))
        seam.register(tool("p2.beta"))

        assertEquals(listOf("p1.alpha", "p2.beta"), seam.definitions().map { it.name })
        assertEquals("p2.beta", seam.get("p2.beta")?.name)
        assertEquals(null, seam.get("missing"))
    }
}
