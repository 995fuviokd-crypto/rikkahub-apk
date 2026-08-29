package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.AgentSubagentConfig
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

private const val TAG = "SubagentTools"

private const val SUBAGENT_MAX_STEPS = 32

private const val SUBAGENT_SYSTEM_PROMPT = """
你是一个被主代理委派任务的子代理（subagent）。你的唯一职责：独立完成委派给你的任务，并输出一份完整、自包含的最终结果。

规则：
1. 直接执行任务，不要向用户提问，也不要请求确认。
2. 你的输出会被主代理读取，用于继续主任务；请确保结果自包含，包含主代理需要的全部信息与结论。
3. 输出最终结果即可，不要输出与任务无关的寒暄或过程叙述。
"""

/**
 * RikkaHub 内置子代理引擎。
 *
 * 对照 DSH tool-subagent 契约实现：
 * - 模型可见 schema 与 DSH 一致：`{ description, prompt }`
 * - 成功只返回子代理的最终文本，中间步骤留在子会话内（上下文隔离）
 * - 失败返回 `Error: <原因>` 文本
 * - depth 达到 maxDepth 后工具仍会注入，但调用直接返回错误（fail loud）
 */
fun createBuiltInSubagentTools(
    config: AgentSubagentConfig,
    settings: Settings,
    generationHandler: GenerationHandler,
    assistant: Assistant,
    parentModel: Model,
    depth: Int = 0,
    runTracker: SubagentRunTracker? = null,
): List<Tool> {
    if (!config.enabled) return emptyList()
    return listOf(
        Tool(
            name = "delegate_subagent",
            description = buildString {
                append("将一个独立子任务委派给子代理执行。")
                if (depth < config.maxDepth) {
                    append("子代理在隔离上下文中运行，其工作过程不会进入当前对话；只返回最终结果。")
                } else {
                    append("注意：当前已达到最大委派深度，再次委派将被拒绝。")
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put(
                            "description",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "任务的简短描述（一句话），用于标识这次委派")
                            }
                        )
                        put(
                            "prompt",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "交给子代理的完整任务指令，必须自包含：子代理看不到当前对话内容")
                            }
                        )
                    },
                    required = listOf("description", "prompt"),
                )
            },
            execute = { args ->
                executeDelegation(
                    config = config,
                    settings = settings,
                    generationHandler = generationHandler,
                    assistant = assistant,
                    parentModel = parentModel,
                    depth = depth,
                    args = args,
                    runTracker = runTracker,
                )
            },
        )
    )
}

private suspend fun executeDelegation(
    config: AgentSubagentConfig,
    settings: Settings,
    generationHandler: GenerationHandler,
    assistant: Assistant,
    parentModel: Model,
    depth: Int,
    args: kotlinx.serialization.json.JsonElement,
    runTracker: SubagentRunTracker? = null,
): List<UIMessagePart> {
    val prompt = (args as? JsonObject)?.get("prompt")?.let { (it as? JsonPrimitive)?.content }
    if (prompt.isNullOrBlank()) {
        return listOf(UIMessagePart.Text("Error: missing prompt"))
    }
    val description = (args as? JsonObject)?.get("description")?.let { (it as? JsonPrimitive)?.content }

    // 深度上限检查：到顶后 fail loud（与 DSH 一致，工具可见但拒绝执行）
    if (depth >= config.maxDepth) {
        return listOf(
            UIMessagePart.Text("Error: 已达到最大委派深度(${config.maxDepth})，禁止继续委派子代理。请自行完成任务。")
        )
    }

    // 子模型解析：配置指定（可为任意提供商的模型）> 跟随主模型
    val childModel = config.modelId
        ?.let { id -> settings.providers.asSequence().flatMap { it.models }.firstOrNull { it.id == id } }
        ?: parentModel

    // 子代理使用干净的助手配置：关闭记忆/技能/本地工具，避免副作用泄漏进用户数据
    val childAssistant = assistant.copy(
        enableMemory = false,
        localTools = emptyList(),
        enabledSkills = emptySet(),
        enableRecentChatsReference = false,
    )

    // 子代理追踪：登记本次委派（模型/描述/深度），结束或失败时更新
    val runId = runTracker?.recordStart(
        model = childModel,
        prompt = description?.takeIf { it.isNotBlank() } ?: prompt,
        depth = depth,
    )

    val chunks = try {
        generationHandler.generateText(
            settings = settings,
            model = childModel,
            messages = listOf(
                UIMessage.system(SUBAGENT_SYSTEM_PROMPT),
                UIMessage.user(prompt),
            ),
            assistant = childAssistant,
            tools = buildList {
                // 受限只读工具集：搜索（若已配置）
                addAll(createSearchTools(settings))
                // 允许嵌套委派时，给子代理注入下一层 delegate 工具
                if (depth + 1 < config.maxDepth) {
                    addAll(
                        createBuiltInSubagentTools(
                            config = config,
                            settings = settings,
                            generationHandler = generationHandler,
                            assistant = assistant,
                            parentModel = childModel,
                            depth = depth + 1,
                            runTracker = runTracker,
                        )
                    )
                }
            },
            maxSteps = SUBAGENT_MAX_STEPS,
        ).last()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        runTracker?.recordEnd(runId.orEmpty(), success = false)
        throw e
    } catch (e: Exception) {
        runTracker?.recordEnd(runId.orEmpty(), success = false)
        Log.e(TAG, "delegate_subagent failed", e)
        return listOf(UIMessagePart.Text("Error: 子代理执行失败 - ${e.message}"))
    }
    runTracker?.recordEnd(runId.orEmpty(), success = true)

    val finalMessages = (chunks as? GenerationChunk.Messages)?.messages.orEmpty()
    val finalText = finalMessages
        .lastOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim()
        .orEmpty()

    return if (finalText.isBlank()) {
        listOf(UIMessagePart.Text("Error: 子代理未产出有效结果"))
    } else {
        listOf(UIMessagePart.Text(finalText))
    }
}
