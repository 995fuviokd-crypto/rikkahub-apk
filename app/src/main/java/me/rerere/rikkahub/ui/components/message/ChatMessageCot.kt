package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart

sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep

    data class InlineText(
        val part: UIMessagePart,
        val index: Int,
    ) : ThinkingStep
}

sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
}

fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()
    var hasThinkingContent = false

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
            hasThinkingContent = false
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
                hasThinkingContent = true
            }

            is UIMessagePart.Tool -> {
                currentThinkingSteps.add(ThinkingStep.ToolStep(part))
                hasThinkingContent = true
            }

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
                hasThinkingContent = true
            }

            is UIMessagePart.Text -> {
                if (hasThinkingContent) {
                    currentThinkingSteps.add(ThinkingStep.InlineText(part, index))
                } else {
                    flushThinkingSteps()
                    result.add(MessagePartBlock.ContentBlock(part, index))
                }
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}
