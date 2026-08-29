package me.rerere.rikkahub.utils

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.TokenEstimate

/**
 * 上下文分类估算：把会话 token 按来源拆分为 MCP 工具、系统工具、活跃消息、历史摘要、系统提示词，
 * 供上下文容量小球点击后的分类占比 Sheet 展示。
 *
 * 说明：活动消息与历史摘要来自 Conversation（与自动压缩估算的同一口径）；
 * 工具定义与系统提示词为运行时注入，由 UI 层调用时传入工具名列表与提示词文本估算。
 */
object ContextBreakdown {
    private const val TOOL_DEF_OVERHEAD_TOKENS = 64

    data class Category(
        val key: String,
        val label: String,
        val tokens: Int,
        val color: Long,
    )

    data class Result(
        val categories: List<Category>,
        val total: Int,
    ) {
        val max: Int get() = categories.maxOfOrNull { it.tokens } ?: 0
    }

    fun estimate(
        conversation: Conversation,
        toolNames: List<String> = emptyList(),
        mcpToolNames: Set<String> = emptySet(),
        systemPrompt: String? = null,
    ): Result {
        var messageTokens = 0
        for (message in conversation.activeMessages) {
            messageTokens += TokenEstimate.estimateMessageTokens(message)
        }

        val summary = conversation.compression?.summary.orEmpty()
        val summaryTokens = TokenEstimate.estimateTokens(summary)

        val mcpTokens = toolNames
            .filter { it in mcpToolNames }
            .sumOf { TOOL_DEF_OVERHEAD_TOKENS + TokenEstimate.estimateTokens(it) }
        val systemToolTokens = toolNames
            .filter { it !in mcpToolNames }
            .sumOf { TOOL_DEF_OVERHEAD_TOKENS + TokenEstimate.estimateTokens(it) }
        val systemPromptTokens = systemPrompt?.let { TokenEstimate.estimateTokens(it) } ?: 0

        val categories = buildList {
            if (messageTokens > 0) {
                add(Category("messages", "活跃消息", messageTokens, 0xFF3B82F6))
            }
            if (summaryTokens > 0) {
                add(Category("summary", "历史摘要", summaryTokens, 0xFF8B5CF6))
            }
            if (mcpTokens > 0) {
                add(Category("mcp", "MCP 工具", mcpTokens, 0xFF10B981))
            }
            if (systemToolTokens > 0) {
                add(Category("system_tools", "系统工具", systemToolTokens, 0xFFF59E0B))
            }
            if (systemPromptTokens > 0) {
                add(Category("system_prompt", "系统提示词", systemPromptTokens, 0xFFEF4444))
            }
        }.ifEmpty {
            listOf(Category("messages", "活跃消息", 0, 0xFF3B82F6))
        }

        return Result(categories, categories.sumOf { it.tokens })
    }
}