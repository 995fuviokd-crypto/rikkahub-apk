package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_28_29 : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 防御性加列：导入备份时数据库 schema 可能与版本号不一致，
        // 若 workspaces 已含 local_directory_uri 列则跳过，避免 duplicate column 崩溃。
        if (!hasColumn(db, "workspaces", "local_directory_uri")) {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `local_directory_uri` TEXT")
        }
    }
}
