package me.rerere.rikkahub.data.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

object Migration_26_27 : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ConversationEntity_assistant_id` ON `conversationentity` (`assistant_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ConversationEntity_folder_id` ON `conversationentity` (`folder_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ConversationEntity_is_pinned_update_at` ON `conversationentity` (`is_pinned`, `update_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoryEntity_assistant_id_is_archived` ON `memoryentity` (`assistant_id`, `is_archived`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoryEntity_scope_key` ON `memoryentity` (`scope_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoryJournalEntity_processed_assistant_id` ON `memoryjournalentity` (`processed`, `assistant_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoryJournalEntity_conversation_id` ON `memoryjournalentity` (`conversation_id`)")
        db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` ON `message_node` (`conversation_id`, `node_index`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_GenMediaEntity_create_at` ON `genmediaentity` (`create_at`)")
    }
}
