package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workflows",
    indices = [
        Index(value = ["updated_at"]),
    ],
)
data class WorkflowEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("description")
    val description: String = "",
    @ColumnInfo("steps_json")
    val stepsJson: String = "[]",
    @ColumnInfo("graph_json")
    val graphJson: String = "{}",
    @ColumnInfo("stats_json")
    val statsJson: String = "{}",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
