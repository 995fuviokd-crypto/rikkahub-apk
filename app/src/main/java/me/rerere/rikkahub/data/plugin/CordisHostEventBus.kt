package me.rerere.rikkahub.data.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import java.util.concurrent.atomic.AtomicLong

/**
 * 宿主事件总线：把 [AppEventBus] 的宿主事件缓冲成带序号的事件流。
 *
 * 双通道（R3.2）：
 * - 推：插件经 [subscribe] 注册订阅（topic 前缀匹配），事件到达即回调，
 *   面板侧由 JS 桥经 evaluateJavascript 主动推送 `CordisBridge.onEvent(type, json)`
 * - 拉：`events.poll {since}` 环形缓冲增量拉取，保留为断线恢复通道
 *   （WebView 重载/断连后用 lastSeq 补齐错过的推送）。
 */
class CordisHostEventBus(
    private val appEventBus: AppEventBus,
    private val scope: CoroutineScope,
) {
    /** 事件条目：seq 单调递增，面板用 since 断点增量拉取。 */
    data class CordisEvent(
        val seq: Long,
        val type: String,
        val payload: JsonObject,
    )

    /** 订阅句柄：同一 pluginId 重复订阅时旧句柄自动失效（替换式去重）。 */
    class Subscription internal constructor(
        val pluginId: String,
        val topics: Set<String>,
    ) {
        @Volatile
        internal var active = false

        @Volatile
        internal var handler: ((CordisEvent) -> Unit)? = null
    }

    private val seqCounter = AtomicLong(0)
    private val buffer = ArrayDeque<CordisEvent>()
    private val subscriptions = java.util.concurrent.CopyOnWriteArrayList<Subscription>()

    @Volatile
    private var started = false

    /**
     * 启动事件收集协程（幂等）。
     *
     * 构造期零副作用：由插件子系统生命周期管理者（CordisRuntimeHost.start）
     * 显式调用，避免"仅注册依赖就把常驻协程拉起来"的隐式启动。
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            appEventBus.events.collect { event ->
                toCordisEvent(event)?.let(::push)
            }
        }
    }

    /**
     * 注册推送订阅（R3.2）：事件到达且 type 匹配任一 topic 前缀时回调 [handler]。
     *
     * - topics 为空或含 "*" 表示订阅全部
     * - 同 pluginId 重复订阅为替换语义：旧句柄失效，仅新订阅生效
     * - handler 异常被吞掉（订阅者缺陷不影响缓冲与其他订阅者）
     * - 回调线程为 [start] 的收集协程，切主线程由调用方负责
     */
    fun subscribe(pluginId: String, topics: Set<String>, handler: (CordisEvent) -> Unit): Subscription {
        // 替换式去重：同插件仅保留最新订阅
        synchronized(this) {
            subscriptions.removeAll { it.pluginId == pluginId }
        }
        val sub = Subscription(pluginId, topics)
        sub.handler = handler
        sub.active = true
        subscriptions += sub
        return sub
    }

    /** 注销订阅（幂等；句柄已被替换/重复注销均为 no-op）。 */
    fun unsubscribe(subscription: Subscription) {
        subscription.active = false
        subscription.handler = null
        subscriptions.removeAll { it === subscription }
    }

    private fun matches(topics: Set<String>, type: String): Boolean =
        topics.isEmpty() || "*" in topics || topics.any { type.startsWith(it) }

    private fun toCordisEvent(event: AppEvent): CordisEvent? = when (event) {
        is AppEvent.ChatGenerationUpdate -> CordisEvent(
            seq = 0,
            type = "chat.generationUpdate",
            payload = buildJsonObject {
                put("conversationId", event.conversationId.toString())
                put("senderName", event.senderName)
                put("text", event.lastMessage.toText().take(200))
            },
        )

        is AppEvent.ChatGenerationEnded -> CordisEvent(
            seq = 0,
            type = "chat.generationEnded",
            payload = buildJsonObject {
                put("conversationId", event.conversationId.toString())
                put("senderName", event.senderName)
                event.contentPreview?.let { put("contentPreview", it.take(500)) }
            },
        )

        // 面板不可感知的内部事件：不进入缓冲
        is AppEvent.Speak,
        AppEvent.OpenUsageAccessSettings,
        -> null
    }

    private fun push(event: CordisEvent) {
        val seq = seqCounter.incrementAndGet()
        val stamped = event.copy(seq = seq)
        synchronized(buffer) {
            buffer.addLast(stamped)
            while (buffer.size > 200) {
                buffer.removeFirst()
            }
        }
        // R3.2 推送通道：匹配订阅即回调；单订阅者异常不影响其他订阅者与缓冲
        subscriptions.forEach { sub ->
            if (!sub.active) return@forEach
            val handler = sub.handler ?: return@forEach
            if (matches(sub.topics, stamped.type)) {
                runCatching { handler(stamped) }
                    .onFailure { android.util.Log.w("CordisHostEventBus", "subscription handler failed: ${sub.pluginId}", it) }
            }
        }
    }

    /** 返回 seq > [since] 的增量事件（按 seq 升序，最多 [limit] 条）。 */
    fun poll(since: Long, limit: Int = 100): List<CordisEvent> = synchronized(buffer) {
        buffer.filter { it.seq > since }.sortedBy { it.seq }.take(limit)
    }

    /** 当前最大序号（新面板首次订阅前可从 0 拉全量）。 */
    fun latestSeq(): Long = seqCounter.get()
}