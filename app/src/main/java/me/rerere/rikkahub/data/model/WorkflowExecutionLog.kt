package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 执行日志级别。
 */
@Serializable
enum class WorkflowLogLevel {
    @SerialName("debug")
    DEBUG,

    @SerialName("warn")
    WARN,

    @SerialName("error")
    ERROR,
}

/**
 * 执行失败阶段：
 *  - WORKFLOW_STARTUP：工作流启动阶段失败（如触发参数解析失败）
 *  - RUNTIME_INITIALIZATION：运行时初始化阶段失败
 *  - WORKFLOW_EXECUTION：执行节点阶段失败
 *  - CANCELLATION：工作流被取消
 */
@Serializable
enum class WorkflowExecutionFailureStage {
    @SerialName("workflow_startup")
    WORKFLOW_STARTUP,

    @SerialName("runtime_initialization")
    RUNTIME_INITIALIZATION,

    @SerialName("workflow_execution")
    WORKFLOW_EXECUTION,

    @SerialName("cancellation")
    CANCELLATION,
}

/**
 * 单条执行日志。
 */
@Serializable
data class WorkflowRunLogEntry(
    val timestamp: Long = 0,
    val level: WorkflowLogLevel = WorkflowLogLevel.DEBUG,
    val message: String = "",
    val nodeId: String? = null,
    val nodeName: String? = null,
)

/**
 * 一次工作流执行的完整记录。
 */
@Serializable
data class WorkflowExecutionRecord(
    val runId: String = "",
    val workflowId: String = "",
    val workflowName: String = "",
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val success: Boolean = false,
    val message: String = "",
    val logs: List<WorkflowRunLogEntry> = emptyList(),
    val failureStage: WorkflowExecutionFailureStage? = null,
    val failureReason: String? = null,
)
