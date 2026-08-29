package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 事件源会话日志表（阶段 2）。
 *
 * 每条记录是 append-only 会话日志中的一条 [me.rerere.rikkahub.data.session.SessionEvent]，
 * 以 JSON 序列化存储于 [SessionEventEntity.payload]；消息历史由日志派生，不再单独持久化。
 */
@Entity(
    tableName = "session_events",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversation_id", "seq"])
    ]
)
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("seq")
    val seq: Long,
    @ColumnInfo("time")
    val time: Long,
    @ColumnInfo("payload")
    val payload: String,
)