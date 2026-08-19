package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v29 是损坏的中间版本：早期构建的 28→29 迁移在 Room 校验前已提交（列已添加但 identity hash 未更新），
 * 且该版本用 `DEFAULT NULL` 加列，SQLite 会把它存为文本默认值 'NULL'，与 v30 schema（无默认值）不一致。
 * 此迁移重建 workspaces 表，保证 local_directory_uri 列存在且无默认值，兼容以下状态：
 * - 干净 v28：28→29 迁移已先加列，此处重建。
 * - 半迁移 v29（崩溃版已提交）：列带 'NULL' 默认值，重建后消除。
 */
object Migration_29_30 : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val hasColumn = db.query("PRAGMA table_info(`workspaces`)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "local_directory_uri") {
                    found = true
                    break
                }
            }
            found
        }
        if (!hasColumn) {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `local_directory_uri` TEXT")
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `workspaces_new` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `root` TEXT NOT NULL,
                `shell_status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_access_at` INTEGER,
                `tool_approvals` TEXT NOT NULL DEFAULT '{}',
                `android_local_access` INTEGER NOT NULL DEFAULT 1,
                `local_directory_uri` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `workspaces_new` (`id`, `name`, `root`, `shell_status`, `created_at`, `updated_at`, `last_access_at`, `tool_approvals`, `android_local_access`, `local_directory_uri`)
            SELECT `id`, `name`, `root`, `shell_status`, `created_at`, `updated_at`, `last_access_at`, `tool_approvals`, `android_local_access`, `local_directory_uri` FROM `workspaces`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `workspaces`")
        db.execSQL("ALTER TABLE `workspaces_new` RENAME TO `workspaces`")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces` (`root`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`)")
    }
}
