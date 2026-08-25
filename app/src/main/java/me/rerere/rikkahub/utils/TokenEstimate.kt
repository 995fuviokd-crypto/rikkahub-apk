package me.rerere.rikkahub.utils

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation

/**
 * 轻量 token 估算：CJK 字符约 1 token/字，其他字符约 1 token/4 字符。
 * 用于自动压缩阈值判断与进度展示，非精确计费。
 */
object TokenEstimate {
    private const val ASCII_CHARS_PER_TOKEN = 4

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var ascii = 0
        for (ch in text) {
            val code = ch.code
            if ((code in 0x4E00..0x9FFF) ||
                (code in 0x3400..0x4DBF) ||
                (code in 0xF900..0xFAFF) ||
                (code in 0x3040..0x30FF) || // Hiragana + Katakana
                (code in 0xAC00..0xD7AF) // Hangul syllables
            ) {
                cjk++
            } else if (!ch.isWhitespace()) {
                ascii++
            }
        }
        return cjk + ascii / ASCII_CHARS_PER_TOKEN
    }

    private fun estimatePartTokens(part: UIMessagePart): Int {
        return when (part) {
            is UIMessagePart.Text -> estimateTokens(part.text)
            is UIMessagePart.Reasoning -> estimateTokens(part.reasoning)
            is UIMessagePart.Tool -> {
                var tokens = estimateTokens(part.toolName) + estimateTokens(part.input)
                part.output.forEach { output ->
                    tokens += estimatePartTokens(output)
                }
                tokens
            }
            is UIMessagePart.ServerTool -> {
                var tokens = estimateTokens(part.toolName)
                part.input?.let { tokens += estimateTokens(it.toString()) }
                part.output?.let { tokens += estimateTokens(it.toString()) }
                tokens
            }
            else -> 0
        }
    }

    fun estimateMessageTokens(message: UIMessage): Int {
        return message.parts.sumOf { estimatePartTokens(it) }
    }

    fun estimateConversationTokens(conversation: Conversation): Int {
        // 与实际发送上下文一致：历史摘要 + 未压缩的活跃消息（已压缩消息不再计入）
        val summary = conversation.compression?.summary.orEmpty()
        return estimateTokens(summary) + conversation.activeMessages.sumOf { estimateMessageTokens(it) }
    }
}
