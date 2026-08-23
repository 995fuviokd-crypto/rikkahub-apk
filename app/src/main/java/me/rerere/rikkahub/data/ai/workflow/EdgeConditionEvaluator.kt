package me.rerere.rikkahub.data.ai.workflow

import me.rerere.rikkahub.data.model.NodeType
import me.rerere.rikkahub.data.model.WorkflowEdge
import me.rerere.rikkahub.data.model.WorkflowNode

/**
 * 节点执行状态（内部调度用）。
 */
sealed interface NodeExecutionState {
    data object Pending : NodeExecutionState
    data object Running : NodeExecutionState
    data class Success(val output: String) : NodeExecutionState
    data class Failed(val error: String) : NodeExecutionState
    data class Skipped(val reason: String) : NodeExecutionState

    val isFinished: Boolean
        get() = this is Success || this is Failed || this is Skipped
}

/**
 * 边条件求值（并兼容 RikkaHub 既有 fromPort 分支语义）。
 *
 * [WorkflowEdge.condition] 取值约定：
 *  - 空/null：源节点成功即沿边传播（默认）；
 *  - "success"/"ok"/"on_success"：仅源成功时传播；
 *  - "error"/"failed"/"on_error"：仅源失败时传播（错误处理分支）；
 *  - "true"/"false"：按源输出的布尔值匹配；
 *  - 其它：作为正则表达式匹配源输出。
 *
 * [WorkflowEdge.fromPort] 兼容 IF 节点："true"/"false" 端口要求源输出布尔值匹配，
 * 其余端口（"out"）不参与判定。
 */
object EdgeConditionEvaluator {

    private val SUCCESS_CONDITIONS = setOf("success", "ok", "on_success")
    private val ERROR_CONDITIONS = setOf("error", "failed", "on_error")

    fun shouldFollow(
        edge: WorkflowEdge,
        sourceNode: WorkflowNode,
        sourceState: NodeExecutionState,
    ): Boolean {
        if (!sourceState.isFinished) return false

        val portOk = when (edge.fromPort) {
            "true" -> sourceState is NodeExecutionState.Success && parseBool(sourceState.output)
            "false" -> sourceState is NodeExecutionState.Success && !parseBool(sourceState.output)
            else -> true
        }
        if (!portOk) return false

        val condition = edge.condition?.trim()?.lowercase()
            ?: return sourceState is NodeExecutionState.Success

        if (condition in ERROR_CONDITIONS) return sourceState is NodeExecutionState.Failed
        if (condition in SUCCESS_CONDITIONS) return sourceState is NodeExecutionState.Success

        val successState = sourceState as? NodeExecutionState.Success ?: return false
        return when (condition) {
            "true" -> parseBool(successState.output)
            "false" -> !parseBool(successState.output)
            else -> runCatching { Regex(edge.condition!!).containsMatchIn(successState.output) }
                .getOrDefault(false)
        }
    }

    /**
     * 该节点失败时是否有可用的错误处理边（on_error）。
     */
    fun hasErrorHandler(
        node: WorkflowNode,
        outgoing: List<WorkflowEdge>,
    ): Boolean = outgoing.any { edge ->
        val c = edge.condition?.trim()?.lowercase()
        c in ERROR_CONDITIONS
    }

    private fun parseBool(text: String): Boolean = when (text.trim().lowercase()) {
        "true", "1", "yes", "success" -> true
        else -> false
    }
}
