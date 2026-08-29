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
 * 宿主事件总线：把 [AppEventBus] 的宿主事件缓冲成带序号的事件流，
 * 供 Cordis 面板插件通过 `events.poll` 增量轮询订阅。
 *
 * 设计动机：面板 JS 通过 seamCall 是"拉"模型（同步返回值），无法被宿主主动推。
 * 因此这里维护一根单调递增序号 + 有界环形缓冲，面板 JS 定期
 * `seamCall("events", "poll", '{"since": <lastSeq>}')` 拉取增量事件，
 * 实现"订阅式"宿主事件感知（如生成完成、流式更新）。
 */
class CordisHostEventBus(
    appEventBus: AppEventBus,
    scope: CoroutineScope,
) {
    /** 事件条目：seq 单调递增，面板用 since 断点增量拉取。 */
    data class CordisEvent(
        val seq: Long,
        val type: String,
        val payload: JsonObject,
    )

    private val seqCounter = AtomicLong(0)
    private val buffer = ArrayDeque<CordisEvent>()

    init {
        scope.launch {
            appEventBus.events.collect { event ->
                toCordisEvent(event)?.let(::push)
            }
        }
    }

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
    }

    /** 返回 seq > [since] 的增量事件（按 seq 升序，最多 [limit] 条）。 */
    fun poll(since: Long, limit: Int = 100): List<CordisEvent> = synchronized(buffer) {
        buffer.filter { it.seq > since }.sortedBy { it.seq }.take(limit)
    }

    /** 当前最大序号（新面板首次订阅前可从 0 拉全量）。 */
    fun latestSeq(): Long = seqCounter.get()
}