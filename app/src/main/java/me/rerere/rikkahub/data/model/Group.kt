package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 群组协作模式。
 */
@Serializable
enum class GroupMode {
    @SerialName("orchestrator_worker")
    ORCHESTRATOR_WORKER,

    @SerialName("pipeline")
    PIPELINE,

    @SerialName("debate")
    DEBATE,
}

/**
 * 群组成员：绑定一个具体模型（快照），运行时按 modelId 解析 Provider。
 */
@Serializable
data class GroupMember(
    val id: String,
    val modelId: Uuid,
    val role: String = "",
    val systemPrompt: String? = null,
)

/**
 * 群组：一组 AI 成员的协作配置。
 */
@Serializable
data class Group(
    val id: String,
    val name: String,
    val mode: GroupMode,
    val members: List<GroupMember> = emptyList(),
    val orchestratorId: String? = null,
    val debateRounds: Int = 3,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val orchestrator: GroupMember? get() = members.find { it.id == orchestratorId }
}

/**
 * 运行状态。
 */
@Serializable
enum class RunStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("success")
    SUCCESS,

    @SerialName("failed")
    FAILED,

    @SerialName("stopped")
    STOPPED,
}

/**
 * 单次群组运行。
 */
@Serializable
data class GroupRun(
    val id: String,
    val groupId: String,
    val mission: String,
    val status: RunStatus = RunStatus.RUNNING,
    val summary: String = "",
    val createdAt: Long = 0,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
)

/**
 * 消息类型。
 */
@Serializable
enum class MessageKind {
    @SerialName("plan")
    PLAN,

    @SerialName("subtask")
    SUBTASK,

    @SerialName("result")
    RESULT,

    @SerialName("reply")
    REPLY,

    @SerialName("system")
    SYSTEM,
}

/**
 * 群组运行中的一条消息。
 */
@Serializable
data class GroupMessage(
    val id: String,
    val runId: String,
    val memberId: String,
    val memberRole: String = "",
    val memberModelName: String = "",
    val content: String,
    val kind: MessageKind,
    val createdAt: Long = 0,
)
