package me.rerere.rikkahub.data.files

import android.content.Context
import android.os.Environment
import me.rerere.workspace.WorkspaceBindMount
import java.io.File

/**
 * 工作区与 Android 本地互通的挂载表单点定义。
 *
 * DI（WorkspaceManager 的 bindMounts）与交互式终端会话共用同一份列表，避免两处漂移：
 * - /skills、/tool_outputs、/upload 为 App 内部目录，始终可读写；
 * - /sdcard 为手机外部存储，需 MANAGE_EXTERNAL_STORAGE 授权（未授权时系统会隐藏其内容，
 *   挂载表仍保留，授权后立即可用）。
 */
object WorkspaceMounts {
    fun androidLocalMounts(context: Context): List<WorkspaceBindMount> = listOf(
        WorkspaceBindMount(File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }, "/skills"),
        WorkspaceBindMount(File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }, "/tool_outputs"),
        WorkspaceBindMount(File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }, "/upload"),
        WorkspaceBindMount(Environment.getExternalStorageDirectory(), "/sdcard"),
    )
}
