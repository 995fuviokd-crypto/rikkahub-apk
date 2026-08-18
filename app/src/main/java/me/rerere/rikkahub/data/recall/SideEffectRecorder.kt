package me.rerere.rikkahub.data.recall

import android.content.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.readClipboardText
import kotlin.uuid.Uuid

/**
 * 副作用记录器：在 AI 生成回复期间记录可回滚的副作用（工作区文件、记忆、剪贴板）。
 * 由 [me.rerere.rikkahub.data.ai.GenerationHandler] 在工具执行前后与记忆回调中调用。
 */
class SideEffectRecorder(
    private val context: Context,
    private val snapshotManager: WorkspaceSnapshotManager,
    private val workspaceRoots: List<String>,
) {
    private val recordId: String = Uuid.random().toString()
    private var snapshotTaken = false

    private val memoryActions = mutableListOf<MemoryActionRecord>()
    private var clipboardBefore: String? = null
    private var clipboardAfter: String? = null

    suspend fun onBeforeTool(toolName: String, args: JsonElement) {
        when {
            toolName.isWorkspaceFileTool() -> ensureSnapshot()
            toolName == "clipboard_tool" && args.action() == "write" && clipboardBefore == null -> {
                clipboardBefore = context.readClipboardText()
            }
        }
    }

    suspend fun onAfterTool(toolName: String, args: JsonElement, result: List<me.rerere.ai.ui.UIMessagePart>) {
        if (toolName == "clipboard_tool" && args.action() == "write") {
            clipboardAfter = args.text()
        }
    }

    fun onMemoryCreate(memory: AssistantMemory, assistantId: String) {
        memoryActions += MemoryActionRecord.Create(memory.id, memory.target, memory.content, memory.summary, assistantId)
    }

    fun onMemoryUpdate(before: AssistantMemory, after: AssistantMemory) {
        memoryActions += MemoryActionRecord.Update(
            before.id, before.content, before.summary, after.content, after.summary
        )
    }

    fun onMemoryDelete(before: AssistantMemory, assistantId: String) {
        memoryActions += MemoryActionRecord.Delete(before.id, before.target, before.content, before.summary, assistantId)
    }

    private suspend fun ensureSnapshot() {
        if (snapshotTaken) return
        snapshotTaken = snapshotManager.ensureBefore(recordId, workspaceRoots)
    }

    fun buildLog(): SideEffectLog = SideEffectLog(
        workspaceSnapshotId = if (snapshotTaken) recordId else null,
        workspaceRoots = workspaceRoots,
        memoryActions = memoryActions.toList(),
        clipboardBefore = clipboardBefore,
        clipboardAfter = clipboardAfter,
    )
}

private fun String.isWorkspaceFileTool(): Boolean =
    startsWith("workspace_write_file") ||
        startsWith("workspace_edit_file") ||
        startsWith("workspace_shell")

private fun JsonElement.action(): String? = jsonObject["action"]?.jsonPrimitive?.contentOrNull

private fun JsonElement.text(): String? = jsonObject["text"]?.jsonPrimitive?.contentOrNull
