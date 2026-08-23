package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_33_34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 工作流执行统计
        if (!hasColumn(db, "workflows", "stats_json")) {
            db.execSQL(
                "ALTER TABLE `workflows` ADD COLUMN `stats_json` TEXT NOT NULL DEFAULT '{}'"
            )
        }
    }
}
