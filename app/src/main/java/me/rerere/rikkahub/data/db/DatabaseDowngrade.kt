package me.rerere.rikkahub.data.db

import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * 官方版（原版 RikkaHub）与修改版数据库 schema 降级工具。
 *
 * 修改版在官方版 v24 基础上新增记忆系统（scope-recall），数据库升级到 v27：
 * - v25: memoryentity 加 8 列，新增 memoryjournalentity 表、memory_fts 虚拟表
 * - v26: workspaces 加 android_local_access 列
 * - v27: 索引变更
 *
 * 官方版只能打开 v24 数据库，导入修改版 v27 备份会因 schema 不兼容崩溃。
 * 因此导出备份时，把数据库降级为官方版 v24 兼容副本。
 */
object DatabaseDowngrade {
    private const val TAG = "DatabaseDowngrade"

    // 官方版 schema version 与其 identity hash（与官方版 AppDatabase_Impl 一致）
    private const val OFFICIAL_SCHEMA_VERSION = 24
    private const val OFFICIAL_IDENTITY_HASH = "0ea1aaebfa031c7995c45a1e35822e1a"

    /**
     * 生成官方版 v24 兼容的数据库副本。
     *
     * @param sourceDb 已 checkpoint 的主数据库文件（rikka_hub.db）
     * @param targetDir 目标临时目录
     * @return 降级后的数据库文件，失败返回 null（调用方应回退到原始导出）
     */
    fun createDowngradedCopy(sourceDb: File, targetDir: File): File? {
        if (!sourceDb.exists()) return null
        val targetDb = File(targetDir, "rikka_hub.db")
        return try {
            sourceDb.copyTo(targetDb, overwrite = true)

            val db = SQLiteDatabase.openDatabase(
                targetDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            db.use { downgrade(it) }

            Log.i(TAG, "createDowngradedCopy: downgraded to v$OFFICIAL_SCHEMA_VERSION")
            targetDb
        } catch (e: Exception) {
            Log.e(TAG, "createDowngradedCopy: failed to downgrade database", e)
            runCatching { targetDb.delete() }
            null
        }
    }

    private fun downgrade(db: SQLiteDatabase) {
        // 转成非 WAL 模式，确保降级数据全部落盘到 db 文件，关闭后无 wal/shm 残留
        db.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() }

        // 删除 v25 新增的 FTS 虚拟表
        db.execSQL("DROP TABLE IF EXISTS memory_fts")
        // 删除 v25 新增的记忆日志表（其索引随表删除）
        db.execSQL("DROP TABLE IF EXISTS memoryjournalentity")

        // 删除引用待删列的索引（v27 新增）
        db.execSQL("DROP INDEX IF EXISTS index_MemoryEntity_scope_key")
        db.execSQL("DROP INDEX IF EXISTS index_MemoryEntity_assistant_id_is_archived")

        // memoryentity 删除 v25 新增的 8 列
        db.execSQL("ALTER TABLE memoryentity DROP COLUMN target")
        db.execSQL("ALTER TABLE memoryentity DROP COLUMN summary")
        db.execSQL("ALTER TABLE memoryentity DROP COLUMN source")
        db.execSQL("ALTER TABLE memoryentity DROP COLUMN scope_key")
        db.execSQL("ALTER TABLE memoryentity DROP COLUMN conversation_id")
        db.execSQL("ALTER TABLE memoryentity DROP COLUMN created_at")
        db.execSQL("ALTER TABLE memoryentity DROP COLUMN updated_at")
        db.execSQL("ALTER TABLE memoryentity DROP COLUMN is_archived")

        // workspaces 删除 v26 新增列
        db.execSQL("ALTER TABLE workspaces DROP COLUMN android_local_access")

        // 回退 schema version 与 identity hash，使官方版 Room 校验通过
        db.execSQL("PRAGMA user_version = $OFFICIAL_SCHEMA_VERSION")
        db.execSQL(
            "UPDATE room_master_table SET identity_hash = '$OFFICIAL_IDENTITY_HASH' WHERE id = 42"
        )
    }
}
