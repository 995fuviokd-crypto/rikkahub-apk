package me.rerere.rikkahub.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.cordis.CordisEventBus
import me.rerere.rikkahub.data.cordis.LlmSeam
import me.rerere.rikkahub.data.cordis.SystemPromptSeam
import me.rerere.rikkahub.data.cordis.ToolsSeam
import me.rerere.rikkahub.data.cordis.ToolSeamDefinition
import me.rerere.rikkahub.data.session.Session
import me.rerere.rikkahub.data.session.SessionEvent
import me.rerere.rikkahub.data.tools.ToolDefinition
import me.rerere.rikkahub.data.tools.ToolPipeline
import me.rerere.rikkahub.data.tools.ToolRegistry

/**
 * Agent 启动器：组合真实宿主能力缝（[LlmSeam] / [SystemPromptSeam] / [ToolsSeam]）
 * 驱动 [AgentLoop]，供生产侧（面板、插件、脚本）发起一轮 agent 循环。
 *
 * 工具来源：每次 [run] 时从 [ToolsSeam] 拉取最新工具集，映射为 agent-loop 的
 * [ToolDefinition]，保证插件经能力缝注册的工具实时可见。
 */
class AgentHost(
    private val eventBus: CordisEventBus,
    private val llm: LlmSeam,
    private val systemPrompt: SystemPromptSeam? = null,
    private val toolsSeam: ToolsSeam? = null,
) {
    /**
     * 运行一轮完整 agent 循环（内部多步 tool calling / steward 判定）。
     *
     * @return 本轮产生的事件源会话日志；调用方负责合并/持久化/推进 UI。
     */
    suspend fun run(
        userContent: String,
        stewardJudge: ((String) -> Boolean)? = null,
    ): Session {
        val registry = ToolRegistry(eventBus)
        val pipeline = ToolPipeline(eventBus)
        toolsSeam?.definitions()?.forEach { seam ->
            registry.register(seam.toToolDefinition())
        }
        val loop = AgentLoop(pipeline, registry)
        return loop.run(userContent, llm, systemPrompt, stewardJudge)
    }
}

/** 把 Cordis 能力缝工具定义映射为 agent-loop 工具定义。 */
internal fun ToolSeamDefinition.toToolDefinition(): ToolDefinition = ToolDefinition(
    name = name,
    description = description,
    schema = schema,
    needsApproval = needsApproval,
    execute = execute,
)

/** 会话摘要：供面板/插件读取一轮 agent 运行结果。 */
fun Session.toAgentSummary(): JsonObject {
    val assistantText = events
        .filterIsInstance<SessionEvent.AssistantMessage>()
        .lastOrNull()
        ?.content
        .orEmpty()
    val error = events
        .filterIsInstance<SessionEvent.ToolResult>()
        .firstOrNull { it.error != null }
        ?.message
    return buildJsonObject {
        put("turnCount", turnCount())
        put("events", length())
        put("assistant", assistantText)
        error?.let { put("error", it) }
    }
}