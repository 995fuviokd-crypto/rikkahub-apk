package me.rerere.rikkahub.data.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.script.ScriptRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * dispatchHook 编排逻辑测试：通过覆写 ScriptRuntime.runHook 隔离 V8 引擎，
 * 专测链式顺序、失败隔离、超时跳过与非对象返回防护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginManagerHookDispatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var pluginsRoot: File
    private lateinit var manager: HookTestablePluginManager

    /** 记录 runHook 调用序列并按脚本返回预设结果 */
    private class RecordingScriptRuntime(context: android.content.Context) : ScriptRuntime(context) {
        data class Call(val pluginId: String, val hookName: String, val payloadJson: String)

        /** runHook 在 Dispatchers.Default 执行，须用并发容器保证跨线程可见 */
        val calls = java.util.concurrent.CopyOnWriteArrayList<Call>()

        /** pluginId -> (ok, 返回 text 值)；text 为 null 表示返回非对象 */
        val scripted = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, String?>>()

        /** pluginId -> 模拟耗时毫秒（验证超时跳过） */
        val delays = java.util.concurrent.ConcurrentHashMap<String, Long>()

        override fun runHook(pluginDir: File, pluginId: String, hookName: String, payloadJson: String): ToolResult {
            calls.add(Call(pluginId, hookName, payloadJson))
            delays[pluginId]?.let { Thread.sleep(it) }
            val script = scripted[pluginId]
                ?: return ToolResult(true, "", buildJsonObject { put("text", payloadText(payloadJson)) })
            val (ok, text) = script
            return when {
                !ok -> ToolResult(false, "script error", null)
                text == null -> ToolResult(true, "", kotlinx.serialization.json.JsonPrimitive("not-an-object"))
                else -> ToolResult(true, "", buildJsonObject { put("text", text) })
            }
        }

        private fun payloadText(payloadJson: String): String =
            (Json.parseToJsonElement(payloadJson).jsonObject["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
    }

    private class HookTestablePluginManager(
        context: android.content.Context,
        overridePluginsRoot: File,
        recordedRuntime: RecordingScriptRuntime,
    ) : PluginManager(context, recordedRuntime) {
        val recorded = recordedRuntime

        private val root = overridePluginsRoot
        override fun getPluginsDir(): File = root
    }

    @Before
    fun setUp() {
        pluginsRoot = tmp.newFolder("plugins")
        manager = HookTestablePluginManager(
            RuntimeEnvironment.getApplication(),
            pluginsRoot,
            RecordingScriptRuntime(RuntimeEnvironment.getApplication()),
        )
    }

    private val runtime: RecordingScriptRuntime get() = manager.recorded

    private fun installPlugin(id: String, hooks: List<PluginHook>) {
        val dir = File(pluginsRoot, id).apply { mkdirs() }
        val hooksJson = hooks.joinToString(",") { h ->
            """{"name":"${h.name}","description":"","timeoutMs":${h.timeoutMs}}"""
        }
        dir.resolve("plugin.json").writeText(
            """{"id":"$id","name":"$id","version":"1.0.0","hooks":[$hooksJson]}""",
        )
        File(dir, "script").apply { mkdirs() }.resolve("main.js").writeText("// stub")
    }

    @Test
    fun `无插件声明该 hook 时 payload 原样返回`() = runBlocking {
        installPlugin("a", hooks = emptyList())
        val payload = buildJsonObject { put("text", "hi") }
        val out = manager.dispatchHook(setOf("a"), PluginHook.MESSAGE_BEFORE_SEND, payload)
        assertEquals(payload, out)
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `单插件声明时调用并采用返回值`() = runBlocking {
        installPlugin("a", listOf(PluginHook(PluginHook.MESSAGE_BEFORE_SEND)))
        runtime.scripted["a"] = true to "rewritten"
        val out = manager.dispatchHook(setOf("a"), PluginHook.MESSAGE_BEFORE_SEND, buildJsonObject { put("text", "orig") })
        assertEquals("rewritten", out["text"]!!.jsonPrimitive.content)
        assertEquals(1, runtime.calls.size)
        assertEquals("orig", runtime.calls[0].payloadText())
    }

    @Test
    fun `多插件按 id 稳定排序链式传递`() = runBlocking {
        installPlugin("b", listOf(PluginHook(PluginHook.MESSAGE_BEFORE_SEND)))
        installPlugin("a", listOf(PluginHook(PluginHook.MESSAGE_BEFORE_SEND)))
        runtime.scripted["b"] = true to "from-b"
        runtime.scripted["a"] = true to "final"

        val out = manager.dispatchHook(setOf("b", "a"), PluginHook.MESSAGE_BEFORE_SEND, buildJsonObject { put("text", "start") })

        assertEquals(listOf("a", "b"), runtime.calls.map { it.pluginId })
        // a 先执行收到原始文本；其输出作为 b 的输入
        assertEquals("start", runtime.calls[0].payloadText())
        assertEquals("final", runtime.calls[1].payloadText())
        assertEquals("from-b", out["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `插件失败被跳过不中断链`() = runBlocking {
        installPlugin("a", listOf(PluginHook(PluginHook.MESSAGE_BEFORE_SEND)))
        installPlugin("b", listOf(PluginHook(PluginHook.MESSAGE_BEFORE_SEND)))
        runtime.scripted["a"] = false to null
        runtime.scripted["b"] = true to "ok-from-b"

        val out = manager.dispatchHook(setOf("a", "b"), PluginHook.MESSAGE_BEFORE_SEND, buildJsonObject { put("text", "seed") })

        assertEquals(2, runtime.calls.size)
        // b 收到的仍是原始 seed（a 失败保持上下文）
        assertEquals("seed", runtime.calls[1].payloadText())
        assertEquals("ok-from-b", out["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `非对象返回保持上一上下文`() = runBlocking {
        installPlugin("a", listOf(PluginHook(PluginHook.MESSAGE_BEFORE_SEND)))
        installPlugin("b", listOf(PluginHook(PluginHook.MESSAGE_BEFORE_SEND)))
        runtime.scripted["a"] = true to null
        runtime.scripted["b"] = true to "after-b"

        val out = manager.dispatchHook(setOf("a", "b"), PluginHook.MESSAGE_BEFORE_SEND, buildJsonObject { put("text", "keep") })

        assertEquals("keep", runtime.calls[1].payloadText())
        assertEquals("after-b", out["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `超时插件的返回被丢弃且链继续`() = runBlocking {
        installPlugin("a-slow", listOf(PluginHook(PluginHook.MESSAGE_BEFORE_SEND, timeoutMs = 100L)))
        installPlugin("z-fast", listOf(PluginHook(PluginHook.MESSAGE_BEFORE_SEND)))
        runtime.delays["a-slow"] = 350L
        runtime.scripted["a-slow"] = true to "late"

        val out = manager.dispatchHook(setOf("a-slow", "z-fast"), PluginHook.MESSAGE_BEFORE_SEND, buildJsonObject { put("text", "x") })

        // a-slow 超时后其结果被丢弃；z-fast 收到的仍是原文 x
        assertEquals("x", runtime.calls.last { it.pluginId == "z-fast" }.payloadText())
        assertEquals("x", out["text"]!!.jsonPrimitive.content)
    }

    private fun RecordingScriptRuntime.Call.payloadText(): String =
        Json.parseToJsonElement(payloadJson).jsonObject["text"]!!.jsonPrimitive.content
}

private fun JsonObject.textValue(): String? = (this["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content
