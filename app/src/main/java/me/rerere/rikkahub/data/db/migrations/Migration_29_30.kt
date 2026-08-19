package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v29 是损坏的中间版本：早期构建的 28→29 迁移在 Room 校验前已提交（列已添加但 identity hash 未更新），
 * 用户设备处于"半迁移"状态。此迁移确保 local_directory_uri 列存在即可，列可能已由损坏版本添加。
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
    }
}
