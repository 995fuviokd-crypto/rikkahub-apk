package me.rerere.rikkahub.data.cordis

import kotlinx.coroutines.CancellationException
import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.LinkedHashMap

private const val TAG = "CordisEventBus"

/** 事件分发模式（对齐 dsh/Cordis 五种语义）。 */
enum class DispatchMode {
    /** 广播：逐个调用全部监听器，忽略返回值；单个异常记录后继续。 */
    Emit,

    /** 并发执行全部监听器，汇总返回。 */
    Parallel,

    /** 顺序执行，按序收集返回。 */
    Serial,

    /** 顺序执行，前一监听器返回值作为后一入参。 */
    Waterfall,

    /** 顺序执行，首个返回非空值即截断并返回。 */
    Bail,
}

@Serializable
data class CordisEvent(
    val name: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

/**
 * Cordis 事件总线：注册表驱动的事件分发核心。
 *
 * 对齐 dsh/Cordis 微内核语义：
 * - 监听器按前缀匹配（`foo` 匹配 `foo/bar`），支持命名空间层级
 * - 支持五种分发模式 [DispatchMode]
 * - 插件之间解耦：发布者不感知消费者
 */
class CordisEventBus {
    private val lock = Any()
    private val listeners = LinkedHashMap<String, MutableList<CordisListener>>()

    /** 注册监听器（前缀匹配），返回句柄。 */
    fun on(pattern: String, handler: suspend (CordisEvent) -> JsonObject?): CordisListenerHandle {
        val key = pattern.trim()
        require(key.isNotEmpty()) { "empty event pattern" }
        val listener = CordisListener(handler = handler)
        synchronized(lock) {
            listeners.getOrPut(key) { mutableListOf() }.add(listener)
        }
        return CordisListenerHandle(key, listener)
    }

    /** 注册监听器并绑定归属插件 ID（用于按插件卸载）。 */
    fun on(pattern: String, pluginId: String, handler: suspend (CordisEvent) -> JsonObject?): CordisListenerHandle {
        val key = pattern.trim()
        require(key.isNotEmpty()) { "empty event pattern" }
        val listener = CordisListener(handler = handler, pluginId = pluginId)
        synchronized(lock) {
            listeners.getOrPut(key) { mutableListOf() }.add(listener)
        }
        return CordisListenerHandle(key, listener)
    }

    /** 注销监听（幂等） */
    fun off(handle: CordisListenerHandle) {
        handle.cancel()
        synchronized(lock) {
            listeners[handle.pattern]?.remove(handle.listener)
        }
    }

    /** 句柄归属插件 ID（未知返回 null）。 */
    fun listenerOwner(handle: CordisListenerHandle): String? = handle.listener.pluginId

    /** Emit 语义广播：忽略返回值，单监听器异常记录后继续。 */
    suspend fun emit(event: CordisEvent) {
        dispatch(DispatchMode.Emit, event)
    }

    /** 按指定模式分发给全部匹配监听器。 */
    suspend fun dispatch(mode: DispatchMode, event: CordisEvent): List<JsonObject?> {
        val targets = matching(event.name)
        if (targets.isEmpty()) return emptyList()
        return dispatchTo(mode, event, targets)
    }

    private suspend fun dispatchTo(
        mode: DispatchMode,
        event: CordisEvent,
        targets: List<CordisListener>,
    ): List<JsonObject?> = when (mode) {
        DispatchMode.Emit -> {
            for (listener in targets) invokeSafe(listener, event)
            emptyList()
        }

        DispatchMode.Parallel -> coroutineScope {
            targets
                .map { listener ->
                    async(Dispatchers.Default, start = CoroutineStart.LAZY) { invokeSafe(listener, event) }
                }
                .map { it.await() }
        }

        DispatchMode.Serial -> targets.map { listener -> invokeSafe(listener, event) }

        DispatchMode.Waterfall -> {
            var input: JsonObject = event.payload
            targets.mapIndexed { index, listener ->
                val carried = if (index == 0) event.payload else input
                val result = invokeSafe(listener, CordisEvent(event.name, carried))
                if (result != null) input = result
                result
            }
        }

        DispatchMode.Bail -> {
            for (listener in targets) {
                val result = invokeSafe(listener, event, stopOnError = true)
                if (result != null) return listOf(result)
            }
            emptyList()
        }
    }

    private fun matching(eventName: String): List<CordisListener> =
        synchronized(lock) {
            listeners.entries
                .asSequence()
                .filter { (pattern, _) -> matches(pattern, eventName) }
                .flatMap { (_, bucket) -> bucket.asSequence() }
                .filter { !it.cancelled }
                .toList()
        }

    private suspend fun invokeSafe(
        listener: CordisListener,
        event: CordisEvent,
        stopOnError: Boolean = false,
    ): JsonObject? = try {
        listener.handler(event)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (stopOnError) throw e
        Log.e(TAG, "", e)
        null
    }

    private companion object {
        /** 前缀匹配：`foo` 匹配 `foo` 与 `foo/bar`。 */
        fun matches(pattern: String, eventName: String): Boolean =
            eventName == pattern ||
                eventName.startsWith(if (pattern.endsWith("/")) pattern else "$pattern/")
    }
}

/** 主题发射器：快速真值（Emit 语义广播） */
suspend fun CordisEventBus.emitNow(name: String, payload: JsonObject = JsonObject(emptyMap())) {
    emit(CordisEvent(name, payload))
}