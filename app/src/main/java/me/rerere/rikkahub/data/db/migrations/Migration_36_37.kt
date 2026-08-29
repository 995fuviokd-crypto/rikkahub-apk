package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v36 -> v37：任务中心
 * - groups 表新增 schedule_cron 列（可空，定时任务 cron 表达式），由 Room AutoMigration 自动添加
 * - 此处仅为查询定时群组建立索引
 */
class Migration_36_37 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_groups_schedule_cron ON `groups` (`schedule_cron`)"
        )
    }
}