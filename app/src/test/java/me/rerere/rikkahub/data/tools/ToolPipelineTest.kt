package me.rerere.rikkahub.data.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.cordis.CordisEvent
import me.rerere.rikkahub.data.cordis.CordisEventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 工具管线测试：事件链顺序、pre-execute 拦截、execute Waterfall 参数透传、
 * allowlist 过滤正确性。
 */
class ToolPipelineTest {

    private fun jstr(s: String) = JsonPrimitive(s)

    private fun echoTool(name: String = "echo"): ToolDefinition =
        ToolDefinition(
            name = name,
            description = "echo",
            schema = buildJsonObject { put("type", jstr("object")) },
            execute = { input -> input },
        )

    @Test
    fun `basic execute without plugins returns output`() = runBlocking {
        val bus = CordisEventBus()
        val pipeline = ToolPipeline(bus)
        val tool = echoTool()
        val input = buildJsonObject { put("text", jstr("hi")) }

        val result = pipeline.execute(tool, input)

        assertTrue(result.isSuccess)
        assertEquals("hi", result.output!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `execute failure captured in result`() = runBlocking {
        val bus = CordisEventBus()
        val pipeline = ToolPipeline(bus)
        val tool = ToolDefinition(
            name = "boom",
            execute = { throw RuntimeException("kaboom") }
        )

        val result = pipeline.execute(tool, buildJsonObject { })

        assertFalse(result.isSuccess)
        assertNotNull(result.error)
        assertEquals("kaboom", result.error!!.message)
    }

    @Test
    fun `event chain order is pre-execute execute post-execute result`() = runBlocking {
        val bus = CordisEventBus()
        val order = mutableListOf<String>()
        bus.on("tools/pre-execute") { order += "pre"; null }
        bus.on("tools/execute") { order += "execute"; null }
        bus.on("tools/post-execute") { order += "post"; null }
        bus.on("tools/result") { order += "result"; null }

        val pipeline = ToolPipeline(bus)
        pipeline.execute(echoTool(), buildJsonObject { put("x", jstr("1")) })

        assertEquals(listOf("pre", "execute", "post", "result"), order)
    }

    @Test
    fun `pre-execute can reject tool call`() = runBlocking {
        val bus = CordisEventBus()
        val pipeline = ToolPipeline(bus)

        // pre-execute 返回 denied: true → 拦截
        bus.on("tools/pre-execute") {
            buildJsonObject {
                put("denied", jstr("true"))
                put("reason", jstr("not allowed"))
            }
        }

        try {
            pipeline.execute(echoTool(), buildJsonObject { })
            fail("pre-execute 拒绝应抛出 ToolExecutionRejected")
        } catch (e: ToolExecutionRejected) {
            assertEquals("not allowed", e.message)
        }
    }

    @Test
    fun `execute waterfall listener can rewrite input`() = runBlocking {
        val bus = CordisEventBus()
        val pipeline = ToolPipeline(bus)
        var executedInput: JsonElement? = null

        // execute 监听器改写 input 传给下一阶段
        bus.on("tools/execute") {
            buildJsonObject {
                put("input", buildJsonObject { put("text", jstr("rewritten")) })
            }
        }

        val tool = echoTool()
        val result = pipeline.execute(tool, buildJsonObject { put("text", jstr("original")) })
        assertEquals("rewritten", result.output!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `registry emits tools change on register`() = runBlocking {
        val bus = CordisEventBus()
        var changedCount = 0
        var lastNames = ""
        bus.on("tools/change") {
            changedCount++
            lastNames = it.payload["tools"]!!.jsonPrimitive.content
            null
        }

        val registry = ToolRegistry(bus)
        registry.register(echoTool("a"))
        registry.register(echoTool("b"))

        assertEquals(2, changedCount)
        assertTrue(lastNames.contains("a"))
        assertTrue(lastNames.contains("b"))
        assertEquals(2, registry.all().size)
    }

    @Test
    fun `registry rejects duplicate names and supports unregister`() = runBlocking {
        val bus = CordisEventBus()
        val registry = ToolRegistry(bus)

        assertTrue(registry.register(echoTool("a")))
        assertFalse(registry.register(echoTool("a")))
        assertEquals(1, registry.all().size)

        assertTrue(registry.unregister("a"))
        assertFalse(registry.unregister("a"))
        assertEquals(0, registry.all().size)
    }

    @Test
    fun `allowlist strips runtime fields recursively`() {
        val schema = buildJsonObject {
            put("type", jstr("object"))
            put("\$internal", jstr("secret"))
            put("hidden", jstr("yes"))
            put("runtime", jstr("no"))
            put("properties", buildJsonObject {
                put("ok", jstr("keep"))
                put("\$secret", jstr("drop"))
                put("runtimeFlag", jstr("drop"))
            })
        }

        val stripped = ToolSchemaAllowlist.strip(schema)!!.jsonObject

        assertNull(stripped["\$internal"])
        assertNull(stripped["hidden"])
        assertNull(stripped["runtime"])
        assertNotNull(stripped["type"])
        assertEquals("keep", stripped["properties"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertNull(stripped["properties"]!!.jsonObject["\$secret"])
        assertNull(stripped["properties"]!!.jsonObject["runtimeFlag"])
    }

    @Test
    fun `allowlist allows normal fields`() {
        assertTrue(ToolSchemaAllowlist.isAllowed("name"))
        assertTrue(ToolSchemaAllowlist.isAllowed("description"))
        assertTrue(ToolSchemaAllowlist.isAllowed("properties"))
        assertFalse(ToolSchemaAllowlist.isAllowed("\$internal"))
        assertFalse(ToolSchemaAllowlist.isAllowed("hidden"))
        assertFalse(ToolSchemaAllowlist.isAllowed("runtime"))
    }
}