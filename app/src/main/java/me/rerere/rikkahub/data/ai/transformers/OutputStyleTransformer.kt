package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OutputStyle

class OutputStyleTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val outputStyle = ctx.settings.outputStyles.firstOrNull { it.id == ctx.assistant.activeOutputStyleId }
            ?: return messages

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        if (systemIndex < 0) return messages

        val systemMessage = messages[systemIndex]
        val originalText = systemMessage.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }

        val newText = if (outputStyle.frontmatter.keepDefaultInstructions) {
            buildString {
                append(originalText)
                appendLine()
                append(outputStyle.instructions)
            }
        } else {
            buildString {
                append("You are ")
                append(ctx.assistant.name.ifBlank { "an assistant" })
                append(". ")
                append(outputStyle.instructions)
            }
        }

        val result = messages.toMutableList()
        result[systemIndex] = systemMessage.copy(
            parts = listOf(UIMessagePart.Text(newText)),
            isSynthetic = true,
        )
        return result
    }
}
