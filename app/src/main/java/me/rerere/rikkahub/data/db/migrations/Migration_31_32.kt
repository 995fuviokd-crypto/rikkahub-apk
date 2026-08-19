package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_31_32 : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 修复 v2.4.12 崩溃用户：其 DB 已提交为 v31 但 workflows 表结构错误
        // （graph_json nullable + index_WorkflowEntity_updated_at），必须在此重建。
        ensureWorkflowsTable(db)

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `groups` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `mode` TEXT NOT NULL,
                `members_json` TEXT NOT NULL,
                `orchestrator_id` TEXT,
                `debate_rounds` INTEGER NOT NULL DEFAULT 3,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_groups_updated_at` ON `groups` (`updated_at`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `group_runs` (
                `id` TEXT NOT NULL,
                `group_id` TEXT NOT NULL,
                `mission` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `started_at` INTEGER NOT NULL,
                `ended_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_runs_group_id` ON `group_runs` (`group_id`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `group_messages` (
                `id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `member_id` TEXT NOT NULL,
                `member_role` TEXT NOT NULL,
                `member_model_name` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_run_id` ON `group_messages` (`run_id`)")
    }
}
