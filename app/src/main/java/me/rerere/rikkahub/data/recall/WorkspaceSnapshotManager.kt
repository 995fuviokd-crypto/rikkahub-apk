package me.rerere.rikkahub.data.recall

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceManager
import java.io.File

/**
 * 工作区文件快照管理：对会话关联的所有工作区 filesDir 做 before/after 双向快照，
 * 支持撤回时还原、恢复时重做。
 */
class WorkspaceSnapshotManager(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
) {
    private val snapshotBase: File = File(context.filesDir, "recall_snapshots")

    /**
     * 惰性建立 before 快照（首次文件副作用前调用）。
     * 若快照已存在则跳过，返回 true。
     */
    suspend fun ensureBefore(recordId: String, roots: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (roots.isEmpty()) return@withContext false
            val beforeDir = File(snapshotBase, "$recordId/before")
            // 已建立则复用
            if (beforeDir.exists() && roots.all { root -> File(beforeDir, root).exists() }) {
                return@withContext true
            }
            roots.forEach { root -> copyFilesDirTo(recordId, root, "before") }
            true
        }.getOrElse {
            Log.w(TAG, "ensureBefore failed: ${it.message}")
            false
        }
    }

    /**
     * 撤回：先把当前状态捕获为 after，再用 before 覆盖 filesDir。
     */
    suspend fun restore(recordId: String, roots: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (roots.isEmpty()) return@withContext false
            // 捕获 after（当前状态）
            roots.forEach { root -> copyFilesDirTo(recordId, root, "after") }
            // 用 before 覆盖
            roots.forEach { root -> applySnapshot(recordId, root, "before") }
            true
        }.getOrElse {
            Log.w(TAG, "restore failed: ${it.message}")
            false
        }
    }

    /**
     * 恢复：用 after 覆盖 filesDir。
     */
    suspend fun redo(recordId: String, roots: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (roots.isEmpty()) return@withContext false
            roots.forEach { root -> applySnapshot(recordId, root, "after") }
            true
        }.getOrElse {
            Log.w(TAG, "redo failed: ${it.message}")
            false
        }
    }

    fun release(recordId: String) {
        File(snapshotBase, recordId).deleteRecursively()
    }

    private fun copyFilesDirTo(recordId: String, root: String, stage: String) {
        val filesDir = workspaceManager.filesDir(root)
        if (!filesDir.exists()) return
        val target = File(snapshotBase, "$recordId/$stage/$root")
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        filesDir.copyRecursively(target, overwrite = true)
    }

    private fun applySnapshot(recordId: String, root: String, stage: String) {
        val snapshotDir = File(snapshotBase, "$recordId/$stage/$root")
        val filesDir = workspaceManager.filesDir(root)
        if (!snapshotDir.exists()) return
        if (filesDir.exists()) filesDir.deleteRecursively()
        filesDir.mkdirs()
        snapshotDir.copyRecursively(filesDir, overwrite = true)
    }

    companion object {
        private const val TAG = "WorkspaceSnapshot"
    }
}
