package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_34_35 : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `workflow_execution_records` (
                `run_id` TEXT NOT NULL PRIMARY KEY,
                `workflow_id` TEXT NOT NULL,
                `workflow_name` TEXT NOT NULL DEFAULT '',
                `started_at` INTEGER NOT NULL,
                `finished_at` INTEGER NOT NULL,
                `success` INTEGER NOT NULL,
                `message` TEXT NOT NULL DEFAULT '',
                `logs_json` TEXT NOT NULL DEFAULT '[]',
                `failure_stage` TEXT,
                `failure_reason` TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_workflow_execution_records_workflow_id " +
                "ON `workflow_execution_records` (`workflow_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_workflow_execution_records_started_at " +
                "ON `workflow_execution_records` (`started_at`)"
        )
    }
}
