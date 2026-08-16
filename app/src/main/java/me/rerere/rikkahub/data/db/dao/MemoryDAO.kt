package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryJournalEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND is_archived = 0 ORDER BY updated_at DESC")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND is_archived = 0 ORDER BY updated_at DESC")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE is_archived = 0 ORDER BY updated_at DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE is_archived = 0 ORDER BY updated_at DESC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Query("SELECT * FROM memoryentity WHERE id IN (:ids)")
    suspend fun getMemoriesByIds(ids: List<Int>): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memoryentity
        WHERE is_archived = 0
          AND scope_key = :scopeKey
        ORDER BY updated_at DESC
        """
    )
    suspend fun getMemoriesByScope(scopeKey: String): List<MemoryEntity>

    /**
     * 按 target 过滤的作用域查询。targets 必须非空，空列表请改用 [getMemoriesByScope]。
     *
     * 说明：不要写成 `(:targets IS NULL OR target IN (:targets))`——Room 会把列表展开为
     * 多个占位符，`(?,?) IS NULL` 在 SQLite 中报 "row value misused"，空列表还会产生语法错误。
     */
    @Query(
        """
        SELECT * FROM memoryentity
        WHERE is_archived = 0
          AND scope_key = :scopeKey
          AND target IN (:targets)
        ORDER BY updated_at DESC
        """
    )
    suspend fun getMemoriesByScopeAndTargets(scopeKey: String, targets: List<String>): List<MemoryEntity>

    /**
     * 助手在指定作用域下的记忆。conversationId 非空时只收窄会话级暂存，
     * conversation_id 为 NULL 的跨会话记忆（durable）始终放行。
     */
    @Query(
        """
        SELECT * FROM memoryentity
        WHERE is_archived = 0
          AND assistant_id = :assistantId
          AND scope_key = :scopeKey
          AND (:conversationId IS NULL OR conversation_id IS NULL OR conversation_id = :conversationId)
        ORDER BY updated_at DESC
        """
    )
    suspend fun getScopedMemoriesOfAssistant(
        assistantId: String,
        scopeKey: String,
        conversationId: String? = null,
    ): List<MemoryEntity>

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)

    @Query("SELECT content FROM memoryentity WHERE assistant_id = :assistantId AND is_archived = 0")
    suspend fun getContentsOfAssistant(assistantId: String): List<String>

    @Query("SELECT * FROM memoryentity WHERE is_archived = 0 AND content = :content LIMIT 1")
    suspend fun findExactDedupe(content: String): MemoryEntity?

    // ---- journal ----

    @Insert
    suspend fun insertJournal(journal: MemoryJournalEntity): Long

    @Query(
        """
        SELECT * FROM memoryjournalentity
        WHERE processed = 0
          AND assistant_id = :assistantId
        ORDER BY created_at ASC
        LIMIT :limit
        """
    )
    suspend fun getUnprocessedJournal(assistantId: String, limit: Int): List<MemoryJournalEntity>

    @Update
    suspend fun updateJournal(journal: MemoryJournalEntity)

    @Query("SELECT * FROM memoryjournalentity WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun getJournalOfConversation(conversationId: String): List<MemoryJournalEntity>

    @Query("SELECT COUNT(*) FROM memoryjournalentity WHERE processed = 0")
    suspend fun countUnprocessedJournal(): Int

    @Query("DELETE FROM memoryjournalentity WHERE conversation_id = :conversationId")
    suspend fun deleteJournalOfConversation(conversationId: String)

    @Query("DELETE FROM memoryjournalentity WHERE id IN (SELECT id FROM memoryjournalentity ORDER BY created_at ASC LIMIT :limit)")
    suspend fun deleteOldestJournal(limit: Int)
}
