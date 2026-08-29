package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.SessionEventEntity

@Dao
interface SessionEventDAO {
    @Query("SELECT * FROM session_events WHERE conversation_id = :conversationId ORDER BY seq ASC")
    suspend fun getEvents(conversationId: String): List<SessionEventEntity>

    @Query(
        "SELECT * FROM session_events WHERE conversation_id = :conversationId " +
            "AND seq > :afterSeq ORDER BY seq ASC"
    )
    suspend fun getEventsAfter(conversationId: String, afterSeq: Long): List<SessionEventEntity>

    @Query("SELECT COUNT(*) FROM session_events WHERE conversation_id = :conversationId")
    suspend fun countEvents(conversationId: String): Int

    @Query("SELECT COALESCE(MAX(seq), 0) FROM session_events WHERE conversation_id = :conversationId")
    suspend fun maxSeq(conversationId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<SessionEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: SessionEventEntity)
}