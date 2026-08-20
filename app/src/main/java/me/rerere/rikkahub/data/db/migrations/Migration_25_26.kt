package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v25 → v26：workspaces 表新增 android_local_access 字段。
 *
 * 控制「Android 本地读写工作区与本地互通」开关，默认开启（1）。
 */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!hasColumn(db, "workspaces", "android_local_access")) {
            db.execSQL("ALTER TABLE workspaces ADD COLUMN android_local_access INTEGER NOT NULL DEFAULT 1")
        }
    }
}
