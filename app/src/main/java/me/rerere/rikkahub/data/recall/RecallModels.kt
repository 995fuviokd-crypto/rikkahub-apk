package me.rerere.rikkahub.data.recall

import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode

/**
 * 按最近一个边界标点把单文本消息节点截断为"保留段 + 撤回段"。
 * 满足以下条件才分段：非空标点串、节点最后一条消息为单 Text part、存在边界标点、
 * 保留段与撤回段均非空白。否则返回 null（调用方退化为整条撤回）。
 */
internal fun computeSegmentedRecall(
    lastNode: MessageNode,
    boundaryPunctuation: String,
): Pair<MessageNode, String>? {
    if (boundaryPunctuation.isEmpty()) return null
    val message = lastNode.messages.lastOrNull() ?: return null
    if (message.parts.size != 1) return null
    val onlyPart = message.parts.single()
    if (onlyPart !is UIMessagePart.Text) return null
    val text = onlyPart.text
    val lastPunctIndex = text.indexOfLast { it in boundaryPunctuation }
    if (lastPunctIndex < 0) return null
    val kept = text.substring(0, lastPunctIndex + 1)
    if (kept.isBlank()) return null
    val trimmed = text.substring(lastPunctIndex + 1)
    if (trimmed.isBlank()) return null
    val trimmedMessage = message.copy(parts = listOf(onlyPart.copy(text = kept)))
    return lastNode.copy(messages = listOf(trimmedMessage)) to trimmed
}

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
