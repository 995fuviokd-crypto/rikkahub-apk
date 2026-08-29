package me.rerere.rikkahub.data.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.dao.SessionEventDAO
import me.rerere.rikkahub.data.db.entity.SessionEventEntity

/**
 * 事件源会话的 Room 持久化仓库。
 *
 * [SessionEvent] 序列化为 JSON 存入 `session_events` 表；恢复时按 seq 升序重放为 [Session]。
 */
class SessionEventRepository(
    private val dao: SessionEventDAO,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun append(conversationId: String, event: SessionEvent) {
        dao.insert(event.toEntity(conversationId))
    }

    suspend fun appendAll(conversationId: String, events: List<SessionEvent>) {
        if (events.isEmpty()) return
        dao.insertAll(events.map { it.toEntity(conversationId) })
    }

    /** 恢复指定会话的完整事件日志（按 seq 升序）。 */
    suspend fun loadSession(conversationId: String): Session {
        val entities = dao.getEvents(conversationId)
        return Session(entities.mapNotNull { it.toEvent() })
    }

    /** 恢复 seq 之后的增量事件。 */
    suspend fun loadAfter(conversationId: String, afterSeq: Long): List<SessionEvent> {
        return dao.getEventsAfter(conversationId, afterSeq).mapNotNull { it.toEvent() }
    }

    suspend fun maxSeq(conversationId: String): Long = dao.maxSeq(conversationId)

    private fun SessionEvent.toEntity(conversationId: String): SessionEventEntity =
        SessionEventEntity(
            conversationId = conversationId,
            seq = seq,
            time = time,
            payload = json.encodeToString(SessionEventWrapper(this)),
        )

    private fun SessionEventEntity.toEvent(): SessionEvent? = runCatching {
        json.decodeFromString<SessionEventWrapper>(payload).event
    }.getOrNull()
}

/** 用于多态序列化 SessionEvent 的外层包装。 */
@Serializable
private data class SessionEventWrapper(val event: SessionEvent)