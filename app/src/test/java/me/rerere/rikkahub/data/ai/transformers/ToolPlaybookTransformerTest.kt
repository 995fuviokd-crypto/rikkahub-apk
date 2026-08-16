package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPlaybookTransformerTest {

    private fun toolCallMessage(): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Text("Let me check the file."),
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "workspace_shell",
                input = """{"command":"ls"}""",
            ),
        ),
    )

    @Test
    fun `should not inject before the first tool call`() {
        val messages = listOf(
            UIMessage.user("读取并分析这个项目"),
        )
        val result = transformPlaybook(messages)
        assertEquals(messages, result)
    }

    @Test
    fun `should inject once after the first tool call`() {
        val messages = listOf(
            UIMessage.user("读取并分析这个项目"),
            toolCallMessage(),
        )
        val result = transformPlaybook(messages)
        val injected = result.filter {
            it.role == MessageRole.USER && it.toText().contains(ToolPlaybookTransformer.PLAYBOOK_MARKER)
        }
        assertEquals(1, injected.size)
    }

    @Test
    fun `should not inject again if already injected`() {
        val messages = listOf(
            UIMessage.user("读取并分析这个项目"),
            toolCallMessage(),
            transformPlaybook(
                listOf(UIMessage.user("读取并分析这个项目"), toolCallMessage())
            ).let { injected ->
                injected
            }.first { it.role == MessageRole.USER },
        )
        val result = transformPlaybook(messages)
        val injectedCount = result.count {
            it.role == MessageRole.USER && it.toText().contains(ToolPlaybookTransformer.PLAYBOOK_MARKER)
        }
        assertEquals(1, injectedCount)
    }

    @Test
    fun `should not inject without any user message`() {
        val messages = listOf(
            toolCallMessage(),
        )
        val result = transformPlaybook(messages)
        assertEquals(messages, result)
    }

    @Test
    fun `playbook should contain progress reporting and workspace rules`() {
        val content = PLAYBOOK_CONTENT
        assertTrue(content.contains(ToolPlaybookTransformer.PLAYBOOK_MARKER))
        assertTrue(content.contains("【进度汇报】"))
        assertTrue(content.contains("workspace_shell"))
        assertTrue(content.contains("workspace_edit_file"))
        assertTrue(content.contains("修改后必须验证"))
        assertTrue(content.contains("【资源与纪律】"))
        assertTrue(content.contains("【思维风格】"))
    }

    @Test
    fun `playbook should reinforce we need thinking anchor after promotion`() {
        assertTrue(PLAYBOOK_CONTENT.contains(TOOL_PLAYBOOK_WE_NEED_LINE))
        assertTrue(TOOL_PLAYBOOK_WE_NEED_LINE.contains("We need"))
    }

    @Test
    fun `injection should not break user to assistant-with-tools adjacency`() {
        val messages = listOf(
            UIMessage.user("读取并分析这个项目"),
            toolCallMessage(),
        )
        val result = transformPlaybook(messages)
        val playbookIndex = result.indexOfFirst {
            it.role == MessageRole.USER && it.toText().contains(ToolPlaybookTransformer.PLAYBOOK_MARKER)
        }
        assertTrue(playbookIndex >= 0)
        val previous = result.getOrNull(playbookIndex - 1)
        val next = result.getOrNull(playbookIndex + 1)
        // playbook 不得夹在 USER 与带工具的 ASSISTANT 之间
        val sandwiched = previous?.role == MessageRole.USER &&
            next?.role == MessageRole.ASSISTANT &&
            next.getTools().isNotEmpty()
        assertFalse(sandwiched)
    }
}
