package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.model.MemoryTarget

/**
 * 记忆记录（truth store）。
 *
 * 移植自 scope-recall-hermes 的 SQLite truth 层：durable 目标（user/memory/project/ops）
 * 跨会话共享，general 为当前会话本地暂存。scopeKey 区分 durable 与 local 作用域，
 * conversationId 标识 general 暂存归属的会话。
 */
@Entity(
    indices = [
        Index(value = ["assistant_id", "is_archived"]),
        Index("scope_key")
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("target")
    val target: String = MemoryTarget.MEMORY.name,
    @ColumnInfo("summary")
    val summary: String? = null,
    @ColumnInfo("source")
    val source: String = "manual",
    @ColumnInfo("scope_key")
    val scopeKey: String = "durable",
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long = 0L,
    @ColumnInfo("updated_at")
    val updatedAt: Long = 0L,
    @ColumnInfo("is_archived")
    val isArchived: Boolean = false,
)
