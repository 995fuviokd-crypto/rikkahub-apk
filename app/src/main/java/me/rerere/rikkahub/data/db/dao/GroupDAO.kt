package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.db.entity.GroupMessageEntity
import me.rerere.rikkahub.data.db.entity.GroupRunEntity

@Dao
interface GroupDAO {
    @Query("SELECT * FROM groups ORDER BY updated_at DESC")
    fun listGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun getGroup(id: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE id = :id")
    fun getGroupFlow(id: String): Flow<GroupEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun deleteGroupById(id: String): Int

    @Query("SELECT * FROM group_runs WHERE group_id = :groupId ORDER BY created_at DESC")
    fun listRuns(groupId: String): Flow<List<GroupRunEntity>>

    @Query("SELECT * FROM group_runs WHERE id = :id")
    suspend fun getRun(id: String): GroupRunEntity?

    @Query("SELECT * FROM group_runs WHERE id = :id")
    fun getRunFlow(id: String): Flow<GroupRunEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: GroupRunEntity)

    @Query("DELETE FROM group_runs WHERE group_id = :groupId")
    suspend fun deleteRunsByGroup(groupId: String)

    @Query("SELECT * FROM group_messages WHERE run_id = :runId ORDER BY created_at ASC")
    fun listMessages(runId: String): Flow<List<GroupMessageEntity>>

    @Query("SELECT * FROM group_messages WHERE run_id = :runId ORDER BY created_at ASC")
    suspend fun getMessages(runId: String): List<GroupMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: GroupMessageEntity)

    @Query("DELETE FROM group_messages WHERE run_id = :runId")
    suspend fun deleteMessagesByRun(runId: String)

    @Query("DELETE FROM group_messages WHERE run_id IN (SELECT id FROM group_runs WHERE group_id = :groupId)")
    suspend fun deleteMessagesByGroup(groupId: String)

    @Transaction
    suspend fun deleteGroupCascade(groupId: String) {
        deleteMessagesByGroup(groupId)
        deleteRunsByGroup(groupId)
        deleteGroupById(groupId)
    }
}
