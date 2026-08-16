package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 对话压缩的纯计算逻辑（与 LLM 调用解耦，便于单元测试）。
 *
 * - keepRecent：保留最近 N 条消息，更早历史交由压缩；
 * - splitChunks：超过单块上限时递归二分，保证块内顺序与原对话一致；
 * - compressionText：提取消息全文（含工具调用/输出），避免摘要只含纯文本造成信息丢失。
 */
object ConversationCompressor {

    const val MAX_MESSAGES_PER_CHUNK = 256

    data class SplitResult(
        val messagesToCompress: List<UIMessage>,
        val messagesToKeep: List<UIMessage>,
    )

    /**
     * 依据 keepRecentMessages 将消息切分为「待压缩」与「保留」两部分。
     * keepRecentMessages <= 0 时压缩全部消息；保留数量不小于消息总数时抛出异常。
     */
    fun splitRecent(allMessages: List<UIMessage>, keepRecentMessages: Int): SplitResult {
        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            return SplitResult(
                messagesToCompress = allMessages.dropLast(keepRecentMessages),
                messagesToKeep = allMessages.takeLast(keepRecentMessages),
            )
        }
        if (keepRecentMessages > 0) {
            throw IllegalArgumentException(
                "keepRecentMessages ($keepRecentMessages) is not smaller than total messages (${allMessages.size})"
            )
        }
        return SplitResult(
            messagesToCompress = allMessages,
            messagesToKeep = emptyList(),
        )
    }

    /**
     * 超过单块上限时递归二分，返回保持原始顺序的分块列表，
     * 每块大小不超过 [MAX_MESSAGES_PER_CHUNK]。
     */
    fun splitChunks(messages: List<UIMessage>): List<List<UIMessage>> {
        if (messages.size <= MAX_MESSAGES_PER_CHUNK) return listOf(messages)
        val mid = messages.size / 2
        val left = splitChunks(messages.subList(0, mid))
        val right = splitChunks(messages.subList(mid, messages.size))
        return left + right
    }

    /** 单块压缩 prompt 的默认 token 预算：保证压缩请求不超出常见模型上下文窗口 */
    const val DEFAULT_TOKEN_BUDGET_PER_CHUNK = 24_000

    /**
     * 按 token 预算分块：逐条累加压缩文本的估算 token 数，
     * 超过预算（或达到单块消息数上限）即切新块，保持原始顺序。
     *
     * 与 [splitChunks]（仅按消息条数）不同，这里同时约束消息数与 token 总量，
     * 避免 256 条长消息拼出几十万 token 的 prompt 导致压缩请求超限失败。
     */
    fun splitChunksByTokens(
        messages: List<UIMessage>,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET_PER_CHUNK,
    ): List<List<UIMessage>> {
        if (messages.isEmpty()) return emptyList()
        val budget = tokenBudget.coerceAtLeast(1)
        val chunks = mutableListOf<MutableList<UIMessage>>()
        var current = mutableListOf<UIMessage>()
        var currentTokens = 0
        messages.forEach { message ->
            val tokens = estimateTokensFor(message)
            if (current.isNotEmpty() && (currentTokens + tokens > budget || current.size >= MAX_MESSAGES_PER_CHUNK)) {
                chunks.add(current)
                current = mutableListOf()
                currentTokens = 0
            }
            current.add(message)
            currentTokens += tokens
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    private fun estimateTokensFor(message: UIMessage): Int {
        // 压缩文本按 maxLength=2000 截断，估算时同样截断，避免高估
        val text = compressionText(message, maxLength = 2000)
        return text.length / 4 + 1
    }

    /**
     * 提取消息用于压缩的完整文本：除纯文本外，还包括推理内容、工具调用（名称/入参/输出）、
     * 服务端工具与文档/图片等非文本内容，减少压缩过程的信息丢失。
     */
    fun compressionText(message: UIMessage, maxLength: Int = 2000): String {
        val body = message.parts.joinToString(separator = "\n") { partText(it, maxLength) }
        val text = "[${message.role.name}]: $body"
        return if (text.length > maxLength) text.take(maxLength) + "..." else text
    }

    private fun partText(part: UIMessagePart, maxLength: Int): String = when (part) {
        is UIMessagePart.Text -> part.text
        is UIMessagePart.Reasoning -> part.reasoning
        is UIMessagePart.Tool -> buildString {
            append("[Tool] ")
            append(part.toolName)
            if (part.input.isNotBlank()) {
                append(": ")
                append(part.input)
            }
            if (part.output.isNotEmpty()) {
                append("\nOutput:\n")
                append(part.output.joinToString(separator = "\n") { partText(it, maxLength) })
            }
        }

        is UIMessagePart.ServerTool -> buildString {
            append("[ServerTool] ")
            append(part.toolName)
            part.input?.let {
                append(": ")
                append(it.toString())
            }
            part.output?.let {
                append("\nOutput:\n")
                append(it.toString())
            }
        }

        is UIMessagePart.Document -> "[Document] ${part.fileName}"
        is UIMessagePart.Image -> "[Image] ${part.url}"
        is UIMessagePart.Video -> "[Video] ${part.url}"
        is UIMessagePart.Audio -> "[Audio] ${part.url}"
        else -> ""
    }

    /**
     * 为压缩摘要补充明确的摘要标记，即使 LLM 未按提示词要求输出标记，
     * 也能让后续上下文与 UI 明确识别这是历史摘要而非用户新消息。
     */
    fun String.markedAsCompressionSummary(): String {
        if (isBlank()) return this
        val markers = listOf(
            "[Summary of previous conversation]",
            "[Compressed Summary]",
            "[Summary]",
            "[压缩摘要]",
            "[對話摘要]",
        )
        if (markers.any { contains(it) }) return this
        return "[Summary of previous conversation]\n$this"
    }
}
