package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_32_33 : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 修复历史错误表名：Room 默认表名为实体类名，期望 `MemoryJournalEntity`，
        // 但 v25 迁移曾用全小写 `memoryjournalentity` 建表，导致 schema 校验失败崩溃。
        // 对已升级到 v25~v32 的旧库（存在小写表）幂等地重命名。
        val hasLegacyJournalTable = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='memoryjournalentity'"
        ).use { it.moveToFirst() }
        if (hasLegacyJournalTable) {
            db.execSQL("ALTER TABLE `memoryjournalentity` RENAME TO `MemoryJournalEntity`")
        }

        db.execSQL(
            "ALTER TABLE `groups` ADD COLUMN `reasoning_level` TEXT NOT NULL DEFAULT 'AUTO'"
        )
        db.execSQL(
            "ALTER TABLE `groups` ADD COLUMN `enable_tools` INTEGER NOT NULL DEFAULT 1"
        )
        db.execSQL(
            "ALTER TABLE `groups` ADD COLUMN `workspace_id` TEXT"
        )
        db.execSQL(
            "ALTER TABLE `group_messages` ADD COLUMN `reasoning` TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE `group_messages` ADD COLUMN `tools` TEXT NOT NULL DEFAULT ''"
        )
    }
}
