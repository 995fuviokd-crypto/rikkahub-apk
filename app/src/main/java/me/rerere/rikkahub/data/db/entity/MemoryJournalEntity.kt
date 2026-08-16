package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 对话 journal 记录（provenance 层）。
 *
 * 移植自 scope-recall-hermes 的 journal_entries：原始对话轮次作为溯源保存，
 * 不参与普通检索，由后台 digest 处理合并为高密度 durable 记忆行。
 */
@Entity(
    indices = [
        Index(value = ["processed", "assistant_id"]),
        Index("conversation_id")
    ]
)
data class MemoryJournalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("role")
    val role: String,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("processed")
    val processed: Boolean = false,
    @ColumnInfo("digest_memory_id")
    val digestMemoryId: Int? = null,
)
