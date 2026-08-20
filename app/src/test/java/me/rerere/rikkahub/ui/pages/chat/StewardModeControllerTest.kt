package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.StewardJudgement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class StewardModeControllerTest {

    private fun conversation(messages: List<UIMessage>): Conversation {
        return Conversation(
            id = Uuid.random(),
            assistantId = DEFAULT_ASSISTANT_ID,
            messageNodes = messages.map { it.toMessageNode() },
        )
    }

    private fun userMessage(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistantMessage(text: String) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private fun controller(
        sent: MutableList<String> = mutableListOf(),
        judge: suspend (String, String) -> StewardJudgement,
    ): Pair<StewardModeController, MutableList<String>> {
        val ctrl = StewardModeController(
            judgeCompletion = judge,
            sendMessage = { sent.add(it) },
        )
        return ctrl to sent
    }

    private fun idleConversation(assistantText: String = "完成") =
        conversation(listOf(userMessage("请实现登录功能"), assistantMessage(assistantText)))

    @Test
    fun `disabled mode ignores idle trigger`() = runBlocking {
        val (ctrl, sent) = controller { _, _ -> StewardJudgement(completed = false) }
        ctrl.onAiIdle(idleConversation())

        assertFalse(ctrl.state.value.enabled)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `blank anchor cannot enable mode`() {
        val (ctrl, _) = controller { _, _ -> StewardJudgement(completed = true) }
        ctrl.enable("   ")

        assertFalse(ctrl.state.value.enabled)
    }

    @Test
    fun `enable anchors instruction and starts monitoring`() {
        val (ctrl, _) = controller { _, _ -> StewardJudgement(completed = true) }
        ctrl.enable("请实现登录功能")

        assertTrue(ctrl.state.value.enabled)
        assertEquals(StewardModeStatus.Monitoring, ctrl.state.value.status)
        assertEquals(0, ctrl.state.value.loopCount)
    }

    @Test
    fun `completed judgement closes mode without sending`() = runBlocking {
        val (ctrl, sent) = controller { _, _ -> StewardJudgement(completed = true, reason = "done") }
        ctrl.enable("请实现登录功能")
        ctrl.onAiIdle(idleConversation())

        assertFalse(ctrl.state.value.enabled)
        assertEquals(StewardModeStatus.Completed, ctrl.state.value.status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `incomplete judgement auto sends next instruction`() = runBlocking {
        val (ctrl, sent) = controller { _, _ ->
            StewardJudgement(completed = false, nextInstruction = "继续完成测试")
        }
        ctrl.enable("请实现登录功能")
        ctrl.onAiIdle(idleConversation("只写了接口"))

        assertTrue(ctrl.state.value.enabled)
        assertEquals(1, ctrl.state.value.loopCount)
        assertEquals(StewardModeStatus.AutoSending, ctrl.state.value.status)
        assertEquals(listOf("继续完成测试"), sent)
    }

    @Test
    fun `reaching max loops closes mode and stops sending`() = runBlocking {
        val (ctrl, sent) = controller { _, _ ->
            StewardJudgement(completed = false, nextInstruction = "再来一轮")
        }
        ctrl.setMaxLoops(2)
        ctrl.enable("请实现登录功能")

        ctrl.onAiIdle(idleConversation("第一轮"))
        ctrl.onAiIdle(idleConversation("第二轮"))

        assertEquals(2, sent.size)
        assertEquals(StewardModeStatus.Stopped, ctrl.state.value.status)
        assertFalse(ctrl.state.value.enabled)

        // 达上限后不再发送
        ctrl.onAiIdle(idleConversation("第三轮"))
        assertEquals(2, sent.size)
    }

    @Test
    fun `missing assistant report stops mode`() = runBlocking {
        val (ctrl, sent) = controller { _, _ ->
            StewardJudgement(completed = false, nextInstruction = "下一步")
        }
        ctrl.enable("请实现登录功能")
        // 只有用户消息，没有助手报告
        ctrl.onAiIdle(conversation(listOf(userMessage("请实现登录功能"))))

        assertFalse(ctrl.state.value.enabled)
        assertEquals(StewardModeStatus.Stopped, ctrl.state.value.status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `judgement exception stops mode safely`() = runBlocking {
        val (ctrl, sent) = controller { _, _ -> error("模型调用失败") }
        ctrl.enable("请实现登录功能")
        ctrl.onAiIdle(idleConversation())

        assertFalse(ctrl.state.value.enabled)
        assertEquals(StewardModeStatus.Stopped, ctrl.state.value.status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `disable is idempotent`() {
        val (ctrl, _) = controller { _, _ -> StewardJudgement(completed = true) }
        ctrl.enable("请实现登录功能")

        ctrl.disable()
        ctrl.disable()

        assertFalse(ctrl.state.value.enabled)
    }

    @Test
    fun `setMaxLoops clamps to valid range`() {
        val (ctrl, _) = controller { _, _ -> StewardJudgement(completed = true) }
        ctrl.setMaxLoops(99)
        assertEquals(StewardModeController.MAX_MAX_LOOPS, ctrl.maxLoops.value)
        ctrl.setMaxLoops(0)
        assertEquals(StewardModeController.MIN_MAX_LOOPS, ctrl.maxLoops.value)
    }

    @Test
    fun `unlimited loops keeps sending beyond max loops`() = runBlocking {
        val (ctrl, sent) = controller { _, _ ->
            StewardJudgement(completed = false, nextInstruction = "再来一轮")
        }
        ctrl.setMaxLoops(1)
        ctrl.setUnlimitedLoops(true)
        ctrl.enable("请实现登录功能")

        // 第 1 轮
        ctrl.onAiIdle(idleConversation("第一轮"))
        // 即使已超过 maxLoops=1，无上限时仍继续发送
        ctrl.onAiIdle(idleConversation("第二轮"))
        ctrl.onAiIdle(idleConversation("第三轮"))

        assertTrue(ctrl.state.value.enabled)
        assertEquals(3, ctrl.state.value.loopCount)
        assertEquals(3, sent.size)
        assertTrue(ctrl.unlimitedLoops.value)
    }

    @Test
    fun `disabling unlimited restores loop limit`() = runBlocking {
        val (ctrl, sent) = controller { _, _ ->
            StewardJudgement(completed = false, nextInstruction = "再来一轮")
        }
        ctrl.setMaxLoops(1)
        ctrl.setUnlimitedLoops(true)
        ctrl.setUnlimitedLoops(false)
        ctrl.enable("请实现登录功能")

        ctrl.onAiIdle(idleConversation("第一轮"))
        assertEquals(1, sent.size)
        assertEquals(StewardModeStatus.Stopped, ctrl.state.value.status)
        assertFalse(ctrl.state.value.enabled)
    }
}
