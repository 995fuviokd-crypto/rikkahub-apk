package me.rerere.rikkahub.data.recall

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.MessageNode

/**
 * 撤回范围：整条消息或按标点分段截断。
 */
enum class RecallMode {
    WHOLE,
    SEGMENTED,
}

/**
 * 记忆副作用操作记录，用于撤回时反向还原、恢复时正向重做。
 */
sealed interface MemoryActionRecord {
    data class Create(
        val id: Int,
        val target: String,
        val content: String,
        val summary: String?,
        val assistantId: String,
    ) : MemoryActionRecord

    data class Update(
        val id: Int,
        val beforeContent: String,
        val beforeSummary: String?,
        val afterContent: String,
        val afterSummary: String?,
    ) : MemoryActionRecord

    data class Delete(
        val id: Int,
        val target: String,
        val content: String,
        val summary: String?,
        val assistantId: String,
    ) : MemoryActionRecord
}

/**
 * 一条 AI 回复期间产生的可回滚副作用日志。
 */
data class SideEffectLog(
    val workspaceSnapshotId: String? = null,
    val workspaceRoots: List<String> = emptyList(),
    val memoryActions: List<MemoryActionRecord> = emptyList(),
    val clipboardBefore: String? = null,
    val clipboardAfter: String? = null,
    val calendarEventIds: List<Long> = emptyList(),
    val volumeStream: Int? = null,
    val volumeBefore: Int? = null,
    val volumeAfter: Int? = null,
) {
    val isEmpty: Boolean
        get() = workspaceSnapshotId == null &&
            memoryActions.isEmpty() &&
            clipboardBefore == null &&
            calendarEventIds.isEmpty() &&
            volumeBefore == null
}

/**
 * 被撤回的消息及其副作用记录，压入撤回历史栈供恢复使用。
 */
data class RecallRecord(
    val node: MessageNode,
    val nodeIndex: Int,
    val sideEffects: SideEffectLog,
    val recallMode: RecallMode,
    val informedAi: Boolean,
    val trimmedText: String? = null,
    val recallMarkerNodeId: Uuid? = null,
)
