package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v24 → v25：记忆系统升级（scope-recall 移植）。
 *
 * memoryentity 增加 scope 维度字段，新增 memoryjournalentity 溯源表。
 * FTS 虚拟表 memory_fts 在数据库 onOpen 回调中创建（与 message_fts 一致）。
 */
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 防御性加列：导入备份时数据库 schema 可能与版本号不一致，已存在则跳过。
        if (!hasColumn(db, "memoryentity", "target")) {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN target TEXT NOT NULL DEFAULT 'MEMORY'")
        }
        if (!hasColumn(db, "memoryentity", "summary")) {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN summary TEXT")
        }
        if (!hasColumn(db, "memoryentity", "source")) {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
        }
        if (!hasColumn(db, "memoryentity", "scope_key")) {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN scope_key TEXT NOT NULL DEFAULT 'durable'")
        }
        if (!hasColumn(db, "memoryentity", "conversation_id")) {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN conversation_id TEXT")
        }
        if (!hasColumn(db, "memoryentity", "created_at")) {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
        }
        if (!hasColumn(db, "memoryentity", "updated_at")) {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        }
        if (!hasColumn(db, "memoryentity", "is_archived")) {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN is_archived INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `MemoryJournalEntity` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `processed` INTEGER NOT NULL DEFAULT 0,
                `digest_memory_id` INTEGER
            )
            """.trimIndent()
        )
    }
}
