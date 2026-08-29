package me.rerere.rikkahub.data.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 工具定义（对齐 design.md §4.1 与 dsh tools 子系统）。
 *
 * - [schema] 为模型可见的 InputSchema（allowlist 剥离 runtime 字段后的纯净 schema）
 * - [execute] 执行工具，返回 JSON 结果
 * - [presentCall] / [presentResult] 产出 UI 词汇 JSON，经 `presentation` 缝渲染
 * - [needsApproval] 对接 `approval` 能力缝
 */
internal class ToolDefinition(
    val name: String,
    val description: String = "",
    val schema: JsonObject? = null,
    val output: JsonObject? = null,
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> JsonElement,
    val presentCall: ((JsonElement) -> JsonElement?)? = null,
    val presentResult: ((JsonElement, JsonElement?) -> JsonElement?)? = null,
)

/** 工具执行结果。 */
internal class ToolExecutionResult(
    val tool: ToolDefinition,
    val input: JsonElement,
    val output: JsonElement? = null,
    val error: Throwable? = null,
) {
    val isSuccess: Boolean get() = error == null && output != null
}

/** 工具执行拦截（pre-execute 阶段拒绝）。 */
internal class ToolExecutionRejected(message: String) : RuntimeException(message)