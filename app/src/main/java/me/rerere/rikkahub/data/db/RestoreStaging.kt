package me.rerere.rikkahub.data.db

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 数据库备份恢复的暂存区管理。
 *
 * 恢复备份时若直接覆盖正在被 Room 连接使用的 rikka_hub.db/-wal/-shm 文件，
 * 会导致已打开连接失效，进程内其他还在访问 DAO 的协程立即崩溃（闪退）。
 * 正确做法是把数据库条目先写入 staging 目录并标记 pending 待应用，
 * 用户确认后退出进程；下次冷启动在 Koin/Room 构建之前把 staging
 * 应用（覆盖）到正式数据库路径，确保数据库永远不在打开状态下被替換。
 */
object RestoreStaging {
    private const val TAG = "RestoreStaging"
    private const val PREF_NAME = "rikkahub_restore_staging"
    private const val KEY_PENDING = "pending"
    private const val STAGING_DIR = "restore_staging"

    /** 存放待应用数据库文件的目录，位于 app filesDir 下 */
    fun stagingDir(context: Context): File = File(context.filesDir, STAGING_DIR)

    /** 是否存在待应用的数据库恢复 */
    fun isPending(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

    /** 标记有待应用数据库恢复 */
    fun markPending(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PENDING, true).apply()
    }

    /** 清除待应用标记并删除暂存文件（恢复失败时调用，避免下次启动错用半成品） */
    fun cleanStaging(context: Context) {
        runCatching { stagingDir(context).deleteRecursively() }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PENDING, false).apply()
    }

    /**
     * 若存在待应用数据库恢复，把 staging 文件覆盖到正式数据库路径。
     * 必须在 Room.databaseBuilder 首次构建（打开连接）之前调用。
     *
     * @return 本次是否实际进行了应用（无 pending 时返回 false）
     */
    fun applyIfPending(context: Context): Boolean {
        if (!isPending(context)) return false

        val dir = stagingDir(context)
        val stagedDb = File(dir, "rikka_hub.db")
        if (!stagedDb.exists()) {
            Log.w(TAG, "applyIfPending: pending flag set but no staged db, cleaning")
            cleanStaging(context)
            return false
        }

        return try {
            val target = context.getDatabasePath("rikka_hub")
            target.parentFile?.mkdirs()
            val targetWal = File(target.parentFile, "rikka_hub-wal")
            val targetShm = File(target.parentFile, "rikka_hub-shm")

            // 备份的数据库以 checkpoint 后 / 降级副本方式导出（非 WAL 或多个文件），
            // 覆盖前删除残留的旧 wal/shm，避免 SQLite 把旧 WAL 应用到新主库
            targetWal.delete()
            targetShm.delete()

            stagedDb.copyTo(target, overwrite = true)
            File(dir, "rikka_hub-wal").takeIf { it.exists() }?.copyTo(targetWal, overwrite = true)
            File(dir, "rikka_hub-shm").takeIf { it.exists() }?.copyTo(targetShm, overwrite = true)

            Log.i(TAG, "applyIfPending: applied staged database to ${target.absolutePath}")
            cleanStaging(context)
            true
        } catch (e: Exception) {
            // 应用失败保留 pending 与 staging 文件，下次冷启动重试，避免丢失恢复数据
            Log.e(TAG, "applyIfPending: failed to apply staged database, will retry on next launch", e)
            false
        }
    }
}