package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.DeepSeekAnchor

/**
 * DeepSeek V4 条件复刻锚定转换器。
 *
 * 仅对 DeepSeek 家族模型且启用 deepSeekAnchorEnabled 时生效：
 * 1. 每轮在 system 消息前注入锚定前缀（口吻指令 + Beyond 档提示词）：
 *    口吻指令是 function calling 下稳定 "We need…" 口吻的核心稳定器；
 * 2. 首轮（首次用户请求、尚无工具调用）额外注入预热锚定：
 *    合成预热消息 + 预录 "We need…" 回复，把协作轨迹锚定进会话历史。
 */
object DeepSeekAnchorTransformer : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!DeepSeekAnchor.isDeepSeekModel(ctx.model.modelId)) return messages
        if (!ctx.assistant.deepSeekAnchorEnabled) return messages

        val withAnchor = DeepSeekAnchor.applyAnchorPrefix(messages)

        val userRound = messages.count { it.role == MessageRole.USER }
        val hasToolCalls = messages.any { it.getTools().isNotEmpty() }
        return if (userRound == 1 && !hasToolCalls) {
            DeepSeekAnchor.applyWarmupAnchor(withAnchor)
        } else {
            withAnchor
        }
    }
}
