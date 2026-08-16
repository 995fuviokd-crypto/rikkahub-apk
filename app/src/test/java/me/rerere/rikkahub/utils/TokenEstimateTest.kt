package me.rerere.rikkahub.utils

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class TokenEstimateTest {

    @Test
    fun `blank text estimates zero tokens`() {
        assertEquals(0, TokenEstimate.estimateTokens(""))
        assertEquals(0, TokenEstimate.estimateTokens("   \n\t "))
    }

    @Test
    fun `ascii text estimates one token per four characters`() {
        assertEquals(2, TokenEstimate.estimateTokens("hello world"))
        assertEquals(1, TokenEstimate.estimateTokens("abcd"))
        assertEquals(0, TokenEstimate.estimateTokens("abc"))
    }

    @Test
    fun `cjk text estimates one token per character`() {
        assertEquals(4, TokenEstimate.estimateTokens("你好世界"))
        assertEquals(2, TokenEstimate.estimateTokens("中文A"))
    }

    @Test
    fun `mixed text combines cjk and ascii estimates`() {
        assertEquals(3, TokenEstimate.estimateTokens("你好 world"))
    }

    @Test
    fun `message text and reasoning parts are both counted`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("hello world"),
                UIMessagePart.Reasoning(reasoning = "思考过程")
            )
        )
        assertEquals(6, TokenEstimate.estimateMessageTokens(message))
    }

    @Test
    fun `tool part counts tool name input and output`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "1",
                    toolName = "search",
                    input = "query",
                    output = listOf(UIMessagePart.Text("result"))
                )
            )
        )
        assertEquals(3, TokenEstimate.estimateMessageTokens(message))
    }

    @Test
    fun `conversation tokens are the sum of its messages`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage.user("你好").toMessageNode(),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("世界"))
                ).toMessageNode()
            )
        )
        assertEquals(4, TokenEstimate.estimateConversationTokens(conversation))
    }
}
