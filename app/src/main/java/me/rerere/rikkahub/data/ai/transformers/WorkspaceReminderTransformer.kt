package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 与 ChatService.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        val readyWorkspaces = ctx.assistant.effectiveWorkspaceIds
            .mapNotNull { id ->
                val ws = workspaceRepository.getById(id.toString()) ?: return@mapNotNull null
                if (ws.shellStatus != WorkspaceShellStatus.READY.name) null else ws
            }
        if (readyWorkspaces.isEmpty()) return messages

        val prompt = buildString {
            readyWorkspaces.forEachIndexed { index, workspace ->
                // 主工作区（第一个）工具无后缀；附加工作区对应带 _2/_3 后缀的工具
                val suffix = if (index == 0) "" else "_${index + 1}"
                append(buildWorkspacePrompt(workspace, ctx.workspaceCwd, suffix))
                appendLine()
            }
        }.trimEnd()

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    cwd: String? = null,
    toolSuffix: String = "",
): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\", running in a sandboxed proot rootfs environment.")
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file$toolSuffix`: read file contents.")
    appendLine("  - `workspace_write_file$toolSuffix` / `workspace_edit_file$toolSuffix`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_shell$toolSuffix`: run shell commands (the files area is mounted at /workspace).")
    appendLine("- Prefer `workspace_shell$toolSuffix` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file$toolSuffix` for targeted edits over rewriting whole files.")
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
    appendLine("- Tool outputs and generated artifacts are mounted at `/tool_outputs`; write files there when you want the app to surface them to the user.")
    if (workspace.androidLocalAccess) {
        appendLine("- Your phone's storage is mounted at `/sdcard` when full storage access is granted. Read or write user files directly under `/sdcard/<path>`; if the directory appears empty, full storage access is not granted yet.")
        if (!workspace.localDirectoryUri.isNullOrBlank()) {
            appendLine("- A user-selected local folder is mounted at `/local`. It stays in sync: files you write under `/local` are copied back to the phone folder, and files the user places there are available to you under `/local`.")
        }
    }
    if (!cwd.isNullOrBlank() && toolSuffix.isEmpty()) {
        appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
    }
    if (toolSuffix.isEmpty()) {
        appendLine("- This is your primary workspace. Prefer using it unless the task specifically requires another bound workspace.")
    } else {
        appendLine("- This is an additional bound workspace. Use `workspace_*$toolSuffix` tools to operate on it; tools without the suffix operate on the primary workspace.")
    }
    append("</workspace>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
