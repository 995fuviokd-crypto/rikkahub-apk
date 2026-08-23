package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workflow_execution_records",
    indices = [
        Index(value = ["workflow_id"]),
        Index(value = ["started_at"]),
    ],
)
data class WorkflowExecutionRecordEntity(
    @PrimaryKey
    @ColumnInfo("run_id")
    val runId: String,
    @ColumnInfo("workflow_id")
    val workflowId: String,
    @ColumnInfo("workflow_name")
    val workflowName: String = "",
    @ColumnInfo("started_at")
    val startedAt: Long = 0,
    @ColumnInfo("finished_at")
    val finishedAt: Long = 0,
    @ColumnInfo("success")
    val success: Boolean = false,
    @ColumnInfo("message")
    val message: String = "",
    @ColumnInfo("logs_json")
    val logsJson: String = "[]",
    @ColumnInfo("failure_stage")
    val failureStage: String? = null,
    @ColumnInfo("failure_reason")
    val failureReason: String? = null,
)
