package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "groups",
    indices = [
        Index(value = ["updated_at"]),
    ],
)
data class GroupEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("mode")
    val mode: String,
    @ColumnInfo("members_json")
    val membersJson: String,
    @ColumnInfo("orchestrator_id")
    val orchestratorId: String? = null,
    @ColumnInfo("debate_rounds")
    val debateRounds: Int = 3,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "group_runs",
    indices = [
        Index(value = ["group_id"]),
    ],
)
data class GroupRunEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("group_id")
    val groupId: String,
    @ColumnInfo("mission")
    val mission: String,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("summary")
    val summary: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("started_at")
    val startedAt: Long = 0,
    @ColumnInfo("ended_at")
    val endedAt: Long = 0,
)

@Entity(
    tableName = "group_messages",
    indices = [
        Index(value = ["run_id"]),
    ],
)
data class GroupMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("run_id")
    val runId: String,
    @ColumnInfo("member_id")
    val memberId: String,
    @ColumnInfo("member_role")
    val memberRole: String = "",
    @ColumnInfo("member_model_name")
    val memberModelName: String = "",
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("kind")
    val kind: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
