package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return transformMessages(ctx, messages, visual = false)
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 生成结束后再应用一次，确保持久化的消息包含非 visualOnly 正则的替换结果，
        // 否则重访对话时 visualTransform 的临时变换会丢失
        return transformMessages(ctx, messages, visual = false)
    }

    private fun transformMessages(
        ctx: TransformerContext,
        messages: List<UIMessage>,
        visual: Boolean,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (assistant.regexes.isEmpty()) return messages // No regexes, return original messages
        return messages.map { message ->
            val scope = when (message.role) {
                MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
                else -> return@map message // Skip non-assistant messages
            }
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            part.copy(text = part.text.replaceRegexes(assistant, scope, visual = visual))
                        }

                        is UIMessagePart.Reasoning -> {
                            part.copy(reasoning = part.reasoning.replaceRegexes(assistant, scope, visual = visual))
                        }

                        else -> part
                    }
                }
            )
        }
    }
}
