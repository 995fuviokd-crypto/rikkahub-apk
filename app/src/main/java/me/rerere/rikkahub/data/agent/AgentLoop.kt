package me.rerere.rikkahub.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.cordis.CordisCapabilityNotDeclaredException
import me.rerere.rikkahub.data.cordis.LlmSeam
import me.rerere.rikkahub.data.cordis.SystemPromptSeam
import me.rerere.rikkahub.data.session.Session
import me.rerere.rikkahub.data.session.SessionEvent
import me.rerere.rikkahub.data.session.TurnEndReason
import me.rerere.rikkahub.data.tools.ToolExecutionRejected
import me.rerere.rikkahub.data.tools.ToolPipeline
import me.rerere.rikkahub.data.tools.ToolRegistry

/**
 * Agent 生成循环：把 ChatService 的生成循环抽取为独立可测类（阶段 5）。
 *
 * 循环体（对齐 dsh agent loop）：
 * turn/start → user/message → request/header → llm.infer → chunk 逐块 →
 * assistant/message(usage) → tool/call → 工具管线执行 → tool/result → 循环 → turn/end
 *
 * 取消/中断：循环中捕获 CancellationException 时以 interrupted: true 收尾。
 * Steward 判定：可选，由调用方提供 [stewardJudge] 回调。
 */
internal class AgentLoop(
    private val toolPipeline: ToolPipeline,
    private val toolRegistry: ToolRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var seq = 0L

    private fun nextSeq(): Long = ++seq
    private fun now(): Long = System.currentTimeMillis()

    /**
     * 运行一轮完整 agent 循环（可能多步 tool calling）。
     *
     * @param userContent 用户输入文本
     * @param llm LLM 能力缝
     * @param systemPrompt 系统提示组装器（可为 null）
     * @param stewardJudge 可选 Steward 判定回调：给定 assistant 答复文本，返回是否应继续下一步
     *
     * @return 从本轮产生的 [Session] 日志（调用方合并到总日志）
     */
    suspend fun run(
        userContent: String,
        llm: LlmSeam,
        systemPrompt: SystemPromptSeam? = null,
        stewardJudge: ((String) -> Boolean)? = null,
    ): Session {
        var session = Session()
        var turn = 0
        var step = 0

        try {
            session = session.append(
                SessionEvent.TurnStart(seq = nextSeq(), time = now(), turn = turn)
            )

            session = session.append(
                SessionEvent.UserMessage(seq = nextSeq(), time = now(), content = userContent)
            )

            while (true) {
                session = session.append(
                    SessionEvent.StepStart(seq = nextSeq(), time = now(), turn = turn, step = step)
                )

                val system = systemPrompt?.assemble()
                val toolNames = toolRegistry.all().map { it.name }
                session = session.append(
                    SessionEvent.RequestHeader(
                        seq = nextSeq(),
                        time = now(),
                        config = buildJsonObject { },
                        system = system,
                        tools = toolNames.ifEmpty { null },
                    )
                )

                val messages = session.deriveHistory()
                val result = llm.infer(buildJsonObject { }, messages)

                for (msg in result.output) {
                    for (part in msg.parts) {
                        val chunk = partToChunk(part)
                        if (chunk != null) {
                            session = session.append(
                                SessionEvent.AssistantChunk(
                                    seq = nextSeq(),
                                    time = now(),
                                    turn = turn,
                                    step = step,
                                    chunk = chunk,
                                )
                            )
                        }
                    }
                }

                val assistantText = result.output.lastOrNull()?.toText() ?: ""
                val reasoning = collectReasoning(result.output)
                session = session.append(
                    SessionEvent.AssistantMessage(
                        seq = nextSeq(),
                        time = now(),
                        turn = turn,
                        step = step,
                        content = assistantText,
                        reasoning = reasoning,
                        usage = result.usage?.let {
                            TokenUsage(
                                promptTokens = it["promptTokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                completionTokens = it["completionTokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            )
                        },
                    )
                )

                val toolCalls = extractToolCalls(result.output)
                if (toolCalls.isEmpty()) {
                    val shouldContinue = stewardJudge?.invoke(assistantText) ?: false
                    if (!shouldContinue) break
                    step++
                    continue
                }

                for (call in toolCalls) {
                    session = session.append(
                        SessionEvent.ToolCall(
                            seq = nextSeq(),
                            time = now(),
                            turn = turn,
                            step = step,
                            callId = call.id,
                            name = call.name,
                            arguments = call.arguments,
                        )
                    )

                    val tool = toolRegistry.get(call.name)
                    if (tool == null) {
                        session = session.append(
                            SessionEvent.ToolResult(
                                seq = nextSeq(),
                                time = now(),
                                turn = turn,
                                step = step,
                                callId = call.id,
                                name = call.name,
                                message = "error: unknown tool '${call.name}'",
                                error = buildJsonObject {
                                    put("error", "unknown tool '${call.name}'")
                                },
                            )
                        )
                        continue
                    }

                    val input = json.parseToJsonElement(call.arguments)
                    try {
                        val execResult = toolPipeline.execute(tool, input)
                        if (execResult.isSuccess) {
                            session = session.append(
                                SessionEvent.ToolResult(
                                    seq = nextSeq(),
                                    time = now(),
                                    turn = turn,
                                    step = step,
                                    callId = call.id,
                                    name = call.name,
                                    message = execResult.output?.toString() ?: "null",
                                )
                            )
                        } else {
                            session = session.append(
                                SessionEvent.ToolResult(
                                    seq = nextSeq(),
                                    time = now(),
                                    turn = turn,
                                    step = step,
                                    callId = call.id,
                                    name = call.name,
                                    message = execResult.error?.message ?: "unknown error",
                                    error = buildJsonObject {
                                        put("error", execResult.error?.message ?: "unknown error")
                                    },
                                )
                            )
                        }
                    } catch (e: ToolExecutionRejected) {
                        session = session.append(
                            SessionEvent.ToolResult(
                                seq = nextSeq(),
                                time = now(),
                                turn = turn,
                                step = step,
                                callId = call.id,
                                name = call.name,
                                message = "rejected: ${e.message}",
                                error = buildJsonObject { put("error", "rejected: ${e.message}") },
                            )
                        )
                    }
                }

                step++
            }

            session = session.append(
                SessionEvent.TurnEnd(
                    seq = nextSeq(),
                    time = now(),
                    turn = turn,
                    reason = TurnEndReason.Completed,
                )
            )
        } catch (e: CancellationException) {
            session = session.append(
                SessionEvent.TurnEnd(
                    seq = nextSeq(),
                    time = now(),
                    turn = turn,
                    reason = TurnEndReason.Interrupted,
                )
            )
            throw SessionCancelledException(session, e)
        } catch (e: CordisCapabilityNotDeclaredException) {
            session = session.append(
                SessionEvent.TurnEnd(
                    seq = nextSeq(),
                    time = now(),
                    turn = turn,
                    reason = TurnEndReason.Error,
                )
            )
            throw e
        }

        return session
    }

    private fun collectReasoning(messages: List<UIMessage>): String? {
        val parts = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Reasoning>()
        return parts.joinToString("") { it.reasoning }.ifBlank { null }
    }

    private fun partToChunk(part: UIMessagePart): StreamChunk? = when (part) {
        is UIMessagePart.Text -> StreamChunk.TextDelta(id = "txt", text = part.text)
        is UIMessagePart.Reasoning -> StreamChunk.ReasoningDelta(id = "rsn", text = part.reasoning)
        else -> null
    }

    private data class ToolCallInfo(val id: String, val name: String, val arguments: String)

    private fun extractToolCalls(messages: List<UIMessage>): List<ToolCallInfo> {
        return messages.flatMap { msg ->
            msg.parts.filterIsInstance<UIMessagePart.Tool>().mapNotNull { tool ->
                if (!tool.isExecuted) {
                    ToolCallInfo(
                        id = tool.toolCallId,
                        name = tool.toolName,
                        arguments = tool.input,
                    )
                } else null
            }
        }
    }
}

internal class SessionCancelledException(
    val session: me.rerere.rikkahub.data.session.Session,
    cause: kotlinx.coroutines.CancellationException,
) : kotlinx.coroutines.CancellationException(cause.message) {
    init {
        initCause(cause)
    }
}