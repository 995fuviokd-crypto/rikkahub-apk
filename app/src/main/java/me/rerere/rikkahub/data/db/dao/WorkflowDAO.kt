package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkflowEntity

@Dao
interface WorkflowDAO {
    @Query("SELECT * FROM workflows ORDER BY updated_at DESC")
    fun listFlow(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows WHERE id = :id")
    suspend fun getById(id: String): WorkflowEntity?

    @Query("SELECT * FROM workflows WHERE id = :id")
    fun getFlow(id: String): Flow<WorkflowEntity?>

    @Query("SELECT * FROM workflows")
    suspend fun getAll(): List<WorkflowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workflow: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
