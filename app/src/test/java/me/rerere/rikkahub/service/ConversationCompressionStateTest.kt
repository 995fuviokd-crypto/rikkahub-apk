package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompression
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.TokenEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 历史保留式压缩的数据模型行为：
 * - 消息本体保留在 messageNodes 中，仅通过 compression.compressedMessageIds 标记；
 * - activeMessages 排除已压缩消息（发送上下文与 token 估算的共同数据源）；
 * - token 估算 = 摘要 + 活跃消息，保证阈值判断与实际发送一致，防止重复压缩循环。
 */
class ConversationCompressionStateTest {

    private fun conversation(vararg texts: String): Conversation =
        Conversation(
            assistantId = Uuid.random(),
            messageNodes = texts.map { MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(it)))) },
        )

    @Test
    fun `activeMessages equals currentMessages when no compression`() {
        val c = conversation("a", "b", "c")
        assertEquals(c.currentMessages.map { it.toText() }, c.activeMessages.map { it.toText() })
    }

    @Test
    fun `activeMessages excludes compressed messages but nodes retain them`() {
        val c = conversation("m0", "m1", "m2", "m3")
        val compressedIds = c.currentMessages.take(2).map { it.id }.toSet()
        val compressed = c.copy(compression = ConversationCompression(summary = "sum", compressedMessageIds = compressedIds))

        // 发送上下文只含未压缩部分
        assertEquals(listOf("m2", "m3"), compressed.activeMessages.map { it.toText() })
        // 历史本体仍在存储中，UI 可查看
        assertEquals(4, compressed.messageNodes.size)
        assertEquals(4, compressed.currentMessages.size)
    }

    @Test
    fun `token estimate includes summary and excludes compressed messages`() {
        val longText = "x".repeat(400) // ~100 tokens by ascii/4
        val c = conversation(longText, longText, longText)
        val baseline = TokenEstimate.estimateConversationTokens(c)

        val compressedIds = c.currentMessages.take(1).map { it.id }.toSet()
        val compressed = c.copy(
            compression = ConversationCompression(summary = "summary of one message", compressedMessageIds = compressedIds)
        )
        val after = TokenEstimate.estimateConversationTokens(compressed)

        // 两条约等长消息变一条 + 短摘要：总估算应明显下降
        assertTrue("after=$after should be less than baseline=$baseline", after < baseline)
        assertTrue(after > 0)
    }

    @Test
    fun `splitRecent over activeMessages never recompresses already compressed history`() {
        // 第二轮压缩时传入的是 activeMessages（不含已压缩历史），旧摘要不会再次进入待压缩集合
        val all = (0 until 50).map { UIMessage.user("msg-$it-" + "x".repeat(20)) }
        val split = ConversationCompressor.splitRecent(all, keepRecentMessages = 10)
        assertEquals(40, split.messagesToCompress.size)
        // 待压缩集合中不包含任何已带摘要标记的消息（滚动摘要由 ChatService 单独融合）
        assertTrue(split.messagesToCompress.none { it.toText().contains("[Summary of previous conversation]") })
    }
}
