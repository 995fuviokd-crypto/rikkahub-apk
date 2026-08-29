package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 群组协作模式。当前仅保留「编排器-工作者」：主编排器拆解任务并分派给工作者，最后汇总结果。
 */
@Serializable
enum class GroupMode {
    @SerialName("orchestrator_worker")
    ORCHESTRATOR_WORKER,
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
 * 群组：一组 AI 成员的协作配置。作为"任务中心"的任务模板，
 * 可配置定时任务（[scheduleCron]），按 cron 表达式周期触发。
 */
@Serializable
data class Group(
    val id: String,
    val name: String,
    val mode: GroupMode = GroupMode.ORCHESTRATOR_WORKER,
    val members: List<GroupMember> = emptyList(),
    val orchestratorId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val scheduleCron: String? = null,
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
    @SerialName("user")
    USER,

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
    val reasoning: String = "",
    val tools: String = "",
    val createdAt: Long = 0,
)

/**
 * 群组运行中成员调用工具的记录（tools 字段以 JSON 数组字符串存储）。
 */
@Serializable
data class GroupToolRecord(
    val name: String,
    val input: String = "",
    val output: String = "",
    val isExecuted: Boolean = true,
)

/**
 * 会话列表群组分区用的群组摘要：展示群组名、最新消息预览与运行状态。
 */
data class GroupSummary(
    val id: String,
    val name: String,
    val latestMessage: String? = null,
    val status: RunStatus? = null,
    val updatedAt: Long = 0,
)
