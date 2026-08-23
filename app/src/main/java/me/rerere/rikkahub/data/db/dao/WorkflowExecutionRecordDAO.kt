package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkflowExecutionRecordEntity

@Dao
interface WorkflowExecutionRecordDAO {
    @Query("SELECT * FROM workflow_execution_records WHERE workflow_id = :workflowId ORDER BY started_at DESC")
    fun listFlow(workflowId: String): Flow<List<WorkflowExecutionRecordEntity>>

    @Query("SELECT * FROM workflow_execution_records WHERE workflow_id = :workflowId ORDER BY started_at DESC LIMIT 1")
    suspend fun getLatest(workflowId: String): WorkflowExecutionRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: WorkflowExecutionRecordEntity)

    @Query("DELETE FROM workflow_execution_records WHERE workflow_id = :workflowId")
    suspend fun deleteByWorkflow(workflowId: String): Int
}
