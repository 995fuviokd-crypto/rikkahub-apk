package me.rerere.rikkahub.data.cordis

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessage

/**
 * 能力缝（capability seams）契约：把 RikkaHub 现有宿主能力以 Cordis ctx 服务暴露给插件。
 *
 * 每个接口对应 design.md §4 映射表的一行。宿主（ChatService 层）在创建内核时注入
 * [CordisHost]；插件必须在内核声明 `capabilities` 白名单，才能经 [CordisContext.seam]
 * 访问对应能力缝（R7.4 未声明能力访问拒绝）。
 */

/** `llm` 能力缝：infer + 路由/容量元数据。 */
interface LlmSeam {
    suspend fun infer(config: JsonObject, messages: List<UIMessage>): LlmSeamResult
}

class LlmSeamResult(
    val output: List<UIMessage>,
    val usage: JsonObject? = null,
    val provider: String = "",
    val model: String = "",
)

/** `tools` 能力缝：ToolDefinition 注册表 + `tools/change` 变更派发。 */
interface ToolsSeam {
    /** 注册工具定义；name 冲突时返回 false。 */
    fun register(tool: ToolSeamDefinition): Boolean

    fun unregister(name: String): Boolean

    fun definitions(): List<ToolSeamDefinition>

    fun get(name: String): ToolSeamDefinition?

    /** 工具集合变化时派发 `tools/change`。 */
    fun notifyChanged()
}

class ToolSeamDefinition(
    val name: String,
    val description: String = "",
    val schema: JsonObject? = null,
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> JsonElement,
)

/** `sessions` 能力缝：append + rebuild context。 */
interface SessionsSeam {
    suspend fun append(event: JsonObject)

    suspend fun rebuildContext(): List<UIMessage>
}

/** `systemPrompt` 能力缝：片段注册 + 顺序组装。 */
interface SystemPromptSeam {
    /** 注册片段，返回顺序号；position 相同按注册序。 */
    fun addFragment(id: String, position: Int = 0, content: () -> String = { "" }): Int

    fun removeFragment(id: String)

    /** 按 position 顺序组装全部片段。 */
    suspend fun assemble(): String
}

/** `fs` / `sandbox` 能力缝：workspace 沙箱只读/可读写文件 API（白名单）。 */
interface FsSeam {
    suspend fun read(path: String): String

    suspend fun write(path: String, content: String)

    suspend fun exists(path: String): Boolean

    suspend fun list(dir: String): List<String>
}

/** `subprocess` / `shell` / `terminal` 能力缝：复用 WorkspaceTerminalSession。 */
interface SubprocessSeam {
    suspend fun run(command: String, cwd: String? = null): SubprocessSeamResult
}

class SubprocessSeamResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/** `approval` 能力缝：请求/批准回调（对接 needsApproval）。 */
interface ApprovalSeam {
    suspend fun request(toolName: String, input: JsonElement): ApprovalSeamDecision
}

enum class ApprovalSeamDecision { APPROVED, DENIED }

/**
 * 宿主能力注入点：内核创建时传入；各能力缝可为 null（未启用）。
 * 实际实现由 ChatService 层提供并注入（阶段 5 agent-loop 对接）。
 */
class CordisHost(
    val llm: LlmSeam? = null,
    val tools: ToolsSeam? = null,
    val sessions: SessionsSeam? = null,
    val systemPrompt: SystemPromptSeam? = null,
    val fs: FsSeam? = null,
    val sandbox: FsSeam? = null,
    val subprocess: SubprocessSeam? = null,
    val shell: SubprocessSeam? = null,
    val terminal: SubprocessSeam? = null,
    val approval: ApprovalSeam? = null,
)