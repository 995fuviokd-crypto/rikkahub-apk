package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_32_33 : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
