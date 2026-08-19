package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_30_31 : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `workflows_new` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `steps_json` TEXT NOT NULL,
                `graph_json` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `workflows_new` (`id`, `name`, `description`, `steps_json`, `graph_json`, `created_at`, `updated_at`)
            SELECT `id`, `name`, `description`, `steps_json`, COALESCE(`graph_json`, '{}'), `created_at`, `updated_at` FROM `workflows`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `workflows`")
        db.execSQL("ALTER TABLE `workflows_new` RENAME TO `workflows`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflows_updated_at` ON `workflows` (`updated_at`)")
    }
}
