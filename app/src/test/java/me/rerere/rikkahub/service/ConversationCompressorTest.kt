package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ConversationCompressor.markedAsCompressionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationCompressorTest {

    private fun messages(count: Int, charsPerMessage: Int = 20): List<UIMessage> =
        (0 until count).map { index ->
            UIMessage.user("msg-$index-" + "x".repeat(charsPerMessage))
        }

    private fun ids(messages: List<UIMessage>) = messages.map { it.toText().substringBefore("-x") }

    /** 短压缩：消息数未超过单块上限，切成单块，最近 N 条保留。 */
    @Test
    fun `short conversation compresses old messages and keeps recent`() {
        val all = messages(count = 50)
        val split = ConversationCompressor.splitRecent(all, keepRecentMessages = 10)

        assertEquals(40, split.messagesToCompress.size)
        assertEquals(10, split.messagesToKeep.size)
        assertEquals(ids(all.dropLast(10)), ids(split.messagesToCompress))
        assertEquals(ids(all.takeLast(10)), ids(split.messagesToKeep))

        val chunks = ConversationCompressor.splitChunksByTokens(split.messagesToCompress)
        assertEquals(1, chunks.size)
        assertEquals(40, chunks.single().size)
    }

    /** 长压缩 300k+：消息数超过单块上限，按 token 预算切块且不丢失消息。 */
    @Test
    fun `long conversation over 300k chars splits into bounded chunks without loss`() {
        val all = messages(count = 600, charsPerMessage = 500)
        val totalChars = all.sumOf { it.toText().length }
        assertTrue("total chars should exceed 300k, got $totalChars", totalChars >= 300_000)

        val split = ConversationCompressor.splitRecent(all, keepRecentMessages = 50)
        assertEquals(550, split.messagesToCompress.size)
        assertEquals(50, split.messagesToKeep.size)

        val chunks = ConversationCompressor.splitChunksByTokens(
            messages = split.messagesToCompress,
            tokenBudget = 5000,
        )
        // 550 条 × 500 字符 ≈ 550×125 token = 68750 token，预算 5000 时切成多块
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.size <= ConversationCompressor.MAX_MESSAGES_PER_CHUNK })

        val flattened = chunks.flatten()
        assertEquals(ids(split.messagesToCompress), ids(flattened))
    }

    /** 顺序保持：按 token 预算分块后块与块拼接顺序与压缩前完全一致。 */
    @Test
    fun `chunks preserve original message order`() {
        val all = messages(count = 600, charsPerMessage = 500)
        val chunks = ConversationCompressor.splitChunksByTokens(all, tokenBudget = 5000)
        assertEquals(ids(all), ids(chunks.flatten()))
    }

    /** keepRecent=0 时压缩全部消息，不保留任何消息。 */
    @Test
    fun `keep recent zero compresses everything`() {
        val all = messages(count = 30)
        val split = ConversationCompressor.splitRecent(all, keepRecentMessages = 0)

        assertEquals(30, split.messagesToCompress.size)
        assertTrue(split.messagesToKeep.isEmpty())
    }

    /** 保留数量不小于消息总数时抛出异常。 */
    @Test
    fun `keep recent not smaller than total throws`() {
        val all = messages(count = 5)
        assertThrows(IllegalArgumentException::class.java) {
            ConversationCompressor.splitRecent(all, keepRecentMessages = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationCompressor.splitRecent(all, keepRecentMessages = 99)
        }
    }

    /** 边界：消息数达到单块上限时按条数切块。 */
    @Test
    fun `chunk boundary at max messages per chunk`() {
        assertTrue(ConversationCompressor.splitChunksByTokens(messages(256)).size == 1)
        assertTrue(ConversationCompressor.splitChunksByTokens(messages(257)).size == 2)
        assertTrue(ConversationCompressor.splitChunksByTokens(messages(257)).all {
            it.size <= ConversationCompressor.MAX_MESSAGES_PER_CHUNK
        })
    }

    /** 空输入不崩溃。 */
    @Test
    fun `empty messages yields empty split`() {
        val split = ConversationCompressor.splitRecent(emptyList(), keepRecentMessages = 0)
        assertTrue(split.messagesToCompress.isEmpty())
        assertTrue(split.messagesToKeep.isEmpty())
        assertTrue(ConversationCompressor.splitChunksByTokens(emptyList()).isEmpty())
    }

    // ---- splitChunksByTokens：按 token 预算分块，避免超长 prompt 超限 ----

    /** 短对话 token 预算内时切成单块。 */
    @Test
    fun `token split keeps single chunk within budget`() {
        val chunks = ConversationCompressor.splitChunksByTokens(messages(count = 20, charsPerMessage = 500))
        assertEquals(1, chunks.size)
        assertEquals(20, chunks.single().size)
    }

    /** 长消息总 token 超预算时正确切分，且顺序保持。 */
    @Test
    fun `token split bounds chunk size by budget`() {
        val all = messages(count = 600, charsPerMessage = 500)
        val budget = 5000
        val chunks = ConversationCompressor.splitChunksByTokens(all, tokenBudget = budget)

        // 600 条 × 500 字符 ≈ 600×125 token = 75000 token，预算 5000 时应切成多块
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.size <= ConversationCompressor.MAX_MESSAGES_PER_CHUNK })
        assertEquals(ids(all), ids(chunks.flatten()))
    }

    /** 按 token 切块后每块估算 token 不超过预算（单条超预算的消息除外）。 */
    @Test
    fun `token split respects budget except oversized single message`() {
        val all = messages(count = 200, charsPerMessage = 1000)
        val budget = 3000
        val chunks = ConversationCompressor.splitChunksByTokens(all, tokenBudget = budget)

        assertEquals(ids(all), ids(chunks.flatten()))
        chunks.forEach { chunk ->
            val tokens = chunk.sumOf { msg ->
                me.rerere.rikkahub.utils.TokenEstimate.estimateTokens(
                    ConversationCompressor.compressionText(msg, maxLength = ConversationCompressor.DEFAULT_MAX_CONTENT_LENGTH)
                )
            }
            // 单条消息 ASCII 1000 字符约 250 token，预算内任意块累计不应超 budget
            assertTrue("chunk tokens $tokens exceed budget $budget", tokens <= budget)
        }
    }

    // ---- compressionText：完整文本提取，避免非文本 part 在压缩摘要中丢失 ----

    private fun toolMessage(toolName: String, input: String, output: String): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call-1",
                toolName = toolName,
                input = input,
                output = listOf(UIMessagePart.Text(output))
            )
        )
    )

    @Test
    fun `compression text includes tool name input and output`() {
        val text = ConversationCompressor.compressionText(
            toolMessage(toolName = "search", input = "query-keyword", output = "result-body")
        )
        assertTrue(text.contains("[Tool] search"))
        assertTrue(text.contains("query-keyword"))
        assertTrue(text.contains("result-body"))
        assertTrue(text.startsWith("[ASSISTANT]:"))
    }

    @Test
    fun `compression text includes reasoning and document parts`() {
        val reasoningMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(reasoning = "推理内容"))
        )
        assertTrue(ConversationCompressor.compressionText(reasoningMsg).contains("推理内容"))

        val docMsg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Document(url = "file:///a.pdf", fileName = "report.pdf"))
        )
        val docText = ConversationCompressor.compressionText(docMsg)
        assertTrue(docText.contains("[Document] report.pdf"))
    }

    @Test
    fun `compression text truncates keeping both head and tail`() {
        val longMsg = UIMessage.user("A".repeat(100) + "B".repeat(100))
        val text = ConversationCompressor.compressionText(longMsg, maxLength = 50)
        // 总长与原截断一致（maxLength + "...")
        assertTrue(text.length <= 53)
        // 中间省略标记存在
        assertTrue(text.contains("..."))
        // 头部保留（含角色前缀与 A 段开头）
        assertTrue(text.startsWith("[USER]: "))
        assertTrue(text.substringAfter("[USER]: ").startsWith("AAA"))
        // 尾部结论保留（B 段结尾）
        assertTrue(text.endsWith("BBB"))
    }

    // ---- markedAsCompressionSummary：摘要标记兜底 ----

    @Test
    fun `summary marker is prepended when missing`() {
        assertEquals(
            "[Summary of previous conversation]\nraw summary",
            "raw summary".markedAsCompressionSummary()
        )
    }

    @Test
    fun `summary marker is not duplicated when already present`() {
        assertEquals(
            "[Summary of previous conversation]\nbody",
            "[Summary of previous conversation]\nbody".markedAsCompressionSummary()
        )
        assertEquals("[Summary] no change", "[Summary] no change".markedAsCompressionSummary())
    }

    @Test
    fun `blank summary marker returns blank`() {
        assertEquals("", "".markedAsCompressionSummary())
        assertEquals("   ", "   ".markedAsCompressionSummary())
    }
}
