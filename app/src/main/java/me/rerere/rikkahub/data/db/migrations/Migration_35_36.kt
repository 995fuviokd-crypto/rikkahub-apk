package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration_35_36 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_session_events_conversation_id_seq " +
                "ON `session_events` (`conversation_id`, `seq`)"
        )
    }
}