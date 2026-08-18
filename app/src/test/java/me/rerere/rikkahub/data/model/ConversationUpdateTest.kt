package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationUpdateTest {

    private fun userMsg(text: String = "hi") = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistantMsg(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `first stream emit adds new assistant node`() {
        val user = userMsg()
        val assistant = assistantMsg("hello")
        val conversation = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            messages = listOf(user.toMessageNode()),
        )

        val updated = conversation.updateCurrentMessages(listOf(user, assistant))

        assertEquals(2, updated.messageNodes.size)
        val assistantNode = updated.messageNodes[1]
        assertEquals(assistant, assistantNode.currentMessage)
    }

    @Test
    fun `subsequent stream emits update existing assistant node in place`() {
        val user = userMsg()
        val assistant1 = assistantMsg("hello")
        val conversation = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            messages = listOf(user.toMessageNode()),
        )

        val first = conversation.updateCurrentMessages(listOf(user, assistant1))
        assertEquals(2, first.messageNodes.size)

        val assistant2 = assistant1.copy(parts = listOf(UIMessagePart.Text("hello world")))
        val second = first.updateCurrentMessages(listOf(user, assistant2))

        assertEquals(2, second.messageNodes.size)
        assertEquals(assistant2, second.messageNodes[1].currentMessage)
        assertEquals(1, second.messageNodes[1].messages.size)
    }

    @Test
    fun `unchanged messages return same instance`() {
        val user = userMsg()
        val assistant = assistantMsg("hello")
        val conversation = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            messages = listOf(user.toMessageNode(), assistant.toMessageNode()),
        )

        val unchanged = conversation.updateCurrentMessages(listOf(user, assistant))

        assertSame(conversation, unchanged)
    }

    @Test
    fun `text change on existing node updates and returns new instance`() {
        val user = userMsg()
        val assistant1 = assistantMsg("hello")
        val conversation = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            messages = listOf(user.toMessageNode(), assistant1.toMessageNode()),
        )

        val assistant2 = assistant1.copy(parts = listOf(UIMessagePart.Text("hello world")))
        val updated = conversation.updateCurrentMessages(listOf(user, assistant2))

        assertNotSame(conversation, updated)
        assertEquals(2, updated.messageNodes.size)
        assertEquals(assistant2, updated.messageNodes[1].currentMessage)
    }
}
