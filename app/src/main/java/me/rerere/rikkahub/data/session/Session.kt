package me.rerere.rikkahub.data.session

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

/**
 * 事件源会话：append-only 会话事件日志。
 *
 * 消息历史不再单独存储，而是由 [deriveHistory] 从日志派生；
 * 会话由可序列化的 [SessionEvent] 序列构成，支持跨端同步与重放。
 */
data class Session(val events: List<SessionEvent> = emptyList()) {

    val isEncryptedEmpty: Boolean get() = events.isEmpty()

    fun length(): Int = events.size

    fun turnCount(): Int = events.count { it is SessionEvent.TurnEnd }

    fun append(event: SessionEvent): Session = copy(events = events + event)

    fun combined(other: Session): Session = copy(events = events + other.events)

    /**
     * 派生该会话目标链上的消息历史。
     *
     * @param chainLen     保留的最近轮次数
     * @param emptyRange   空/自动补全范围
     * @param visibleRange 保留的可见上下文轮次数
     * @param targetChain  目标链；当前为 0（单链投影，后续由 MessageNode 树多链接入）
     */
    fun deriveHistory(
        chainLen: Int = 5,
        emptyRange: Int = 1,
        visibleRange: Int = 2,
        targetChain: Int = 0,
    ): List<UIMessage> = deriveHistory(this, targetChain)

    fun deriveRequestHeader(): SessionEvent.RequestHeader? =
        events.filterIsInstance<SessionEvent.RequestHeader>().lastOrNull()

    companion object {
        fun deriveHistory(session: Session, targetChain: Int = 0): List<UIMessage> {
            val output = mutableListOf<UIMessage>()

            var curText = ""
            var curReasoning = ""
            val curToolCalls = linkedMapOf<String, UIMessagePart.Tool>()
            var hasChunk = false
            var stepMessage: UIMessage? = null

            fun flushStep() {
                val message = stepMessage ?: return
                val parts = mutableListOf<UIMessagePart>()
                if (curReasoning.isNotBlank()) {
                    parts.add(UIMessagePart.Reasoning(curReasoning))
                }
                if (curText.isNotBlank()) {
                    parts.add(UIMessagePart.Text(curText))
                }
                parts.addAll(curToolCalls.values)
                if (parts.isNotEmpty()) {
                    output.add(message.copy(parts = parts))
                }
                curText = ""
                curReasoning = ""
                curToolCalls.clear()
                hasChunk = false
                stepMessage = null
            }

            fun epochMsToLocalDateTime(epochMs: Long): LocalDateTime =
                Instant.fromEpochMilliseconds(epochMs)
                    .toLocalDateTime(TimeZone.currentSystemDefault())

            for (event in session.events) {
                when (event) {
                    is SessionEvent.UserMessage -> {
                        flushStep()
                        output.add(
                            UIMessage(
                                role = MessageRole.USER,
                                parts = listOf(UIMessagePart.Text(event.content)),
                                createdAt = epochMsToLocalDateTime(event.time),
                            )
                        )
                    }

                    is SessionEvent.StepStart -> {
                        flushStep()
                        curText = ""
                        curReasoning = ""
                        curToolCalls.clear()
                        hasChunk = false
                        stepMessage = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                            createdAt = epochMsToLocalDateTime(event.time),
                        )
                    }

                    is SessionEvent.StepEnd -> flushStep()

                    is SessionEvent.TurnStart,
                    is SessionEvent.TurnEnd -> flushStep()

                    is SessionEvent.AssistantChunk -> {
                        hasChunk = true
                        if (stepMessage == null) {
                            stepMessage = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = emptyList(),
                                createdAt = epochMsToLocalDateTime(event.time),
                            )
                        }
                        when (val chunk = event.chunk) {
                            is StreamChunk.TextDelta -> curText += chunk.text
                            is StreamChunk.TextStart,
                            is StreamChunk.TextEnd -> Unit
                            is StreamChunk.ReasoningDelta -> curReasoning += chunk.text
                            is StreamChunk.ReasoningStart,
                            is StreamChunk.ReasoningEnd -> Unit
                            else -> Unit
                        }
                    }

                    is SessionEvent.AssistantMessage -> {
                        if (!hasChunk) {
                            flushStep()
                            stepMessage = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = emptyList(),
                                createdAt = epochMsToLocalDateTime(event.time),
                            )
                            hasChunk = true
                            if (event.content.isNotBlank()) curText += event.content
                            if (!event.reasoning.isNullOrBlank()) curReasoning += event.reasoning
                        }
                    }

                    is SessionEvent.ToolCall -> {
                        if (stepMessage == null) {
                            stepMessage = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = emptyList(),
                            )
                        }
                        val existing = curToolCalls[event.callId]
                        curToolCalls[event.callId] = UIMessagePart.Tool(
                            toolCallId = event.callId,
                            toolName = event.name,
                            input = event.arguments,
                            output = existing?.output ?: emptyList(),
                        )
                    }

                    is SessionEvent.ToolResult -> {
                        val existing = curToolCalls[event.callId]
                        curToolCalls[event.callId] = UIMessagePart.Tool(
                            toolCallId = event.callId,
                            toolName = event.name,
                            input = existing?.input ?: "{}",
                            output = listOf(UIMessagePart.Text(event.message)),
                        )
                    }

                    is SessionEvent.RequestHeader,
                    is SessionEvent.RequestContext,
                    is SessionEvent.EndSeed -> Unit
                }
            }
            flushStep()
            return output
        }
    }
}