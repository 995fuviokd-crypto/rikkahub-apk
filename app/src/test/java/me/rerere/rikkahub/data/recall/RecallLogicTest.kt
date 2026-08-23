package me.rerere.rikkahub.data.recall

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallLogicTest {

    private fun messageNode(vararg parts: UIMessagePart): MessageNode =
        MessageNode.of(UIMessage(role = MessageRole.USER, parts = parts.toList()))

    private fun textPart(text: String) = UIMessagePart.Text(text)

    @Test
    fun `segmented recall keeps text up to last boundary punctuation`() {
        val node = messageNode(textPart("报告完毕。请继续下一步"))
        val (keptNode, trimmed) = computeSegmentedRecall(node, "。！？～")!!
        assertEquals("报告完毕。", keptNode.currentMessage.toText())
        assertEquals("请继续下一步", trimmed)
    }

    @Test
    fun `segmented recall finds last punctuation among multiple`() {
        val node = messageNode(textPart("先完成第一步。第二步！继续～剩余尾部"))
        val (keptNode, trimmed) = computeSegmentedRecall(node, "。！？～")!!
        assertEquals("先完成第一步。第二步！继续～", keptNode.currentMessage.toText())
        assertEquals("剩余尾部", trimmed)
    }

    @Test
    fun `segmented recall returns null when no boundary punctuation`() {
        val node = messageNode(textPart("没有标点的消息内容"))
        assertNull(computeSegmentedRecall(node, "。！？～"))
    }

    @Test
    fun `segmented recall returns null on empty boundary punctuation config`() {
        val node = messageNode(textPart("内容。尾部"))
        assertNull(computeSegmentedRecall(node, ""))
    }

    @Test
    fun `segmented recall returns null when trimmed part is blank`() {
        val node = messageNode(textPart("内容。"))
        assertNull(computeSegmentedRecall(node, "。！？～"))
    }

    @Test
    fun `segmented recall keeps punctuation mark when it is the only kept content`() {
        val node = messageNode(textPart("。尾部"))
        val (keptNode, trimmed) = computeSegmentedRecall(node, "。！？～")!!
        assertEquals("。", keptNode.currentMessage.toText())
        assertEquals("尾部", trimmed)
    }

    @Test
    fun `segmented recall returns null for multi-part messages`() {
        val node = messageNode(textPart("正文一。"), textPart("正文二。"))
        assertNull(computeSegmentedRecall(node, "。！？～"))
    }

    @Test
    fun `segmented recall returns null for empty messages`() {
        val node = messageNode()
        assertNull(computeSegmentedRecall(node, "。！？～"))
    }

    @Test
    fun `uncommitted side effect log is considered empty`() {
        assertTrue(SideEffectLog().isEmpty)
    }

    @Test
    fun `side effect log with any reversible effect is not empty`() {
        assertFalse(SideEffectLog(workspaceSnapshotId = "s1", workspaceRoots = listOf("w1")).isEmpty)
        assertFalse(SideEffectLog(clipboardBefore = "text").isEmpty)
        assertFalse(SideEffectLog(calendarEventIds = listOf(1L)).isEmpty)
        assertFalse(SideEffectLog(volumeStream = 3, volumeBefore = 5).isEmpty)
        assertFalse(
            SideEffectLog(memoryActions = listOf(MemoryActionRecord.Create(1, "t", "c", null, "a"))).isEmpty
        )
    }
}