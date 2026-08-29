package me.rerere.rikkahub.data.cordis

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cordis 内核单元测试：五种分发模式、inject 拓扑/循环依赖、effect 逆序、作用域恢复与隔离。
 */
class CordisKernelTest {

    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun value(o: JsonObject?, key: String = "value"): String =
        o!![key]!!.jsonPrimitive.content

    // ---- 事件分发五种模式 ----

    @Test
    fun `Emit 模式逐个调用并忽略返回值`() = runBlocking {
        val bus = CordisEventBus()
        val calls = mutableListOf<String>()
        bus.on("foo") { event -> calls += "a:${value(event.payload, "k")}"; null }
        bus.on("foo/bar") { event -> calls += "b:${value(event.payload, "k")}"; null }

        bus.emit(CordisEvent("foo/bar", obj("k" to "1")))

        assertEquals("前缀匹配 foo 应同时匹配 foo/bar", listOf("a:1", "b:1"), calls)
    }

    @Test
    fun `Emit 单监听器异常不中断广播`() = runBlocking {
        val bus = CordisEventBus()
        val calls = mutableListOf<String>()
        bus.on("foo") { error("boom") }
        bus.on("foo") { calls += "second"; null }

        bus.emit(CordisEvent("foo"))

        assertEquals("首监听器异常后仍应调用后续监听器", listOf("second"), calls)
    }

    @Test
    fun `Parallel 模式全部监听器被调用并汇总返回值`() = runBlocking {
        val bus = CordisEventBus()
        val executed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        bus.on("foo") {
            executed += "a"
            obj("value" to "a")
        }
        bus.on("foo") {
            executed += "b"
            obj("value" to "b")
        }

        val results = bus.dispatch(DispatchMode.Parallel, CordisEvent("foo"))

        assertEquals("并发模式应执行全部监听器", setOf("a", "b"), executed)
        assertEquals("并发模式应汇总全部返回值", setOf("a", "b"), results.map { value(it) }.toSet())
    }

    @Test
    fun `Serial 模式顺序收集返回值`() = runBlocking {
        val bus = CordisEventBus()
        val order = mutableListOf<String>()
        bus.on("foo") { order += "1"; obj("value" to "a") }
        bus.on("foo") { order += "2"; obj("value" to "b") }

        val results = bus.dispatch(DispatchMode.Serial, CordisEvent("foo"))

        assertEquals(listOf("1", "2"), order)
        assertEquals(listOf("a", "b"), results.map { value(it) })
    }

    @Test
    fun `Waterfall 模式前值喂后_入参`() = runBlocking {
        val bus = CordisEventBus()
        val received = mutableListOf<String>()
        bus.on("foo") { obj("k" to "v1") }
        bus.on("foo") { event ->
            received += value(event.payload, "k")
            obj("k" to "v2")
        }
        bus.on("foo") { event ->
            received += value(event.payload, "k")
            null
        }

        bus.dispatch(DispatchMode.Waterfall, CordisEvent("foo", obj("k" to "seed")))

        assertEquals("第2监听器收到第1返回，第3收到第2返回", listOf("v1", "v2"), received)
    }

    @Test
    fun `Bail 模式首个真值截断`() = runBlocking {
        val bus = CordisEventBus()
        val calls = mutableListOf<String>()
        bus.on("foo") { calls += "1"; null }
        bus.on("foo") { calls += "2"; obj("value" to "stop") }
        bus.on("foo") { calls += "3"; obj("value" to "never") }

        val results = bus.dispatch(DispatchMode.Bail, CordisEvent("foo"))

        assertEquals("第三个监听器不应被调用", listOf("1", "2"), calls)
        assertEquals(listOf("stop"), results.map { value(it) })
    }

    @Test
    fun `监听注销后不再被调用`() = runBlocking {
        val bus = CordisEventBus()
        var calls = 0
        val handle = bus.on("foo") { calls++; null }
        bus.emit(CordisEvent("foo"))
        handle.cancel()
        bus.emit(CordisEvent("foo"))
        assertEquals("注销后不应再收到事件", 1, calls)
    }

    // ---- inject 拓扑 ----

    @Test
    fun `inject 依赖保证依赖插件先加载`() = runBlocking {
        val kernel = CordisKernel()
        val order = mutableListOf<String>()
        val a = CordisPlugin("a") { order += "a" }
        val b = CordisPlugin("b", inject = listOf("a")) { order += "b" }

        kernel.register(a)
        kernel.register(b)
        assertEquals("b 依赖 a，a 应先 apply", listOf("a", "b"), order)

        kernel.unregister("b")
        kernel.unregister("a")
    }

    @Test
    fun `循环依赖抛出异常`() {
        val kernel = CordisKernel()
        val a = CordisPlugin("a", inject = listOf("b"))
        val b = CordisPlugin("b", inject = listOf("a"))

        kernel.register(a)
        try {
            kernel.register(b)
            assertFalse("循环依赖时应抛异常", true)
        } catch (e: CordisCycleDependencyException) {
            assertTrue("异常信息应包含依赖名", e.message.orEmpty().contains("b"))
        }
    }

    @Test
    fun `插件 apply 失败时回滚并抛异常`() {
        val kernel = CordisKernel()
        val failing = CordisPlugin("bad") { error("apply boom") }
        try {
            kernel.register(failing)
            assertFalse("失败插件应抛异常", true)
        } catch (e: CordisPluginApplyException) {
            assertTrue("插件不得注册", kernel.pluginsState.value.isEmpty())
        }
    }

    // ---- effect 逆序 ----

    @Test
    fun `effect 逆序执行`() = runBlocking {
        val kernel = CordisKernel()
        val order = mutableListOf<String>()
        val plugin = CordisPlugin("p") {
            effect("first") { order += "dispose:1" }
            effect("second") { order += "dispose:2" }
        }
        kernel.register(plugin)
        kernel.unregister("p")
        assertEquals("后注册的 effect 先回收", listOf("dispose:2", "dispose:1"), order)
    }

    @Test
    fun `插件卸载后其服务不再可见`() = runBlocking {
        val kernel = CordisKernel()
        val plugin = CordisPlugin("p") {
            set("some-service", "value")
        }
        kernel.register(plugin)
        assertNotNull(kernel.rootContext.get("some-service"))
        kernel.unregister("p")
        assertNull(kernel.rootContext.get("some-service"))
    }

    @Test
    fun `插件内注册的事件监听随核销毁解除`() = runBlocking {
        val kernel = CordisKernel()
        var calls = 0
        kernel.register(CordisPlugin("p") {
            on("foo") { calls++; null }
        })
        kernel.eventBus.emit(CordisEvent("foo"))
        assertEquals(1, calls)
        kernel.dispose()
        kernel.eventBus.emit(CordisEvent("foo"))
        assertEquals("销毁后监听器不再接收", 1, calls)
    }

    // ---- 作用域 ----

    @Test
    fun `子作用域服务覆盖且不影响父链`() = runBlocking {
        val kernel = CordisKernel()
        kernel.rootContext.set("svc", "parent")
        val child = kernel.rootContext.child()
        child.set("svc", "child")

        assertEquals("child", child.get("svc"))
        assertEquals("父作用域不受子覆盖影响", "parent", kernel.rootContext.get("svc"))
        child.dispose()
        assertEquals("parent", kernel.rootContext.get("svc"))
    }

    @Test
    fun `子作用域继承父服务并回收`() = runBlocking {
        val kernel = CordisKernel()
        kernel.rootContext.set("shared", "v")
        val child = kernel.rootContext.child()
        assertEquals("子作用域冒泡读取父服务", "v", child.get("shared"))
        child.dispose()
        assertEquals("父服务不被子回收影响", "v", kernel.rootContext.get("shared"))
    }

    @Test
    fun `命令与模型注册可见`() = runBlocking {
        val kernel = CordisKernel()
        kernel.register(CordisPlugin("p") {
            command("say", "说话") { obj("echo" to "hi") }
            model("gpt", buildJsonObject { put("name", "gpt-4") })
        })
        val command = kernel.rootContext.commands().firstOrNull { it.name == "say" }
        assertNotNull(command)
        val result = kernel.rootContext.call("say", JsonObject(emptyMap()))
        assertEquals("echo", "hi", value(result, "echo"))
        assertEquals(1, kernel.rootContext.models().size)
        kernel.unregister("p")
    }

    @Test
    fun `调用未注册服务抛错`() = runBlocking {
        val kernel = CordisKernel()
        val threw = try {
            kernel.rootContext.call("missing", JsonObject(emptyMap()))
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue("调用不存在的服务应抛 IllegalStateException", threw)
    }
}