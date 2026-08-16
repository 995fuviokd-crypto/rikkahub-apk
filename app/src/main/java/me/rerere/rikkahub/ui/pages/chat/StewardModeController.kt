package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.StewardJudgement

enum class StewardModeStatus {
    Idle,
    Monitoring,
    Judging,
    AutoSending,
    Completed,
    Stopped,
}

data class StewardModeState(
    val enabled: Boolean = false,
    val checking: Boolean = false,
    val loopCount: Int = 0,
    val status: StewardModeStatus = StewardModeStatus.Idle,
)

/**
 * 智能托管模式状态机。
 *
 * 会话级内存状态，不落库。开启时固定锚定用户原始指令，AI 空闲时自动判断任务是否完成，
 * 未完成则由当前模型生成下一步指令并自动发送，直到完成或达到循环轮数上限。
 *
 * 依赖以函数注入，便于单元测试。
 */
class StewardModeController(
    private val judgeCompletion: suspend (anchorInstruction: String, lastAssistantReport: String) -> StewardJudgement,
    private val sendMessage: (content: String) -> Unit,
) {
    private val _state = MutableStateFlow(StewardModeState())
    val state: StateFlow<StewardModeState> = _state.asStateFlow()

    private val _maxLoops = MutableStateFlow(DEFAULT_MAX_LOOPS)
    val maxLoops: StateFlow<Int> = _maxLoops.asStateFlow()

    private var anchorInstruction: String? = null

    fun setMaxLoops(value: Int) {
        _maxLoops.value = value.coerceIn(MIN_MAX_LOOPS, MAX_MAX_LOOPS)
    }

    /**
     * 开启托管模式，锚定用户原始指令。
     */
    fun enable(anchorInstruction: String) {
        if (anchorInstruction.isBlank()) return
        this.anchorInstruction = anchorInstruction
        _state.value = StewardModeState(
            enabled = true,
            checking = false,
            loopCount = 0,
            status = StewardModeStatus.Monitoring,
        )
    }

    /**
     * 关闭托管模式。幂等。
     */
    fun disable(status: StewardModeStatus = StewardModeStatus.Idle) {
        anchorInstruction = null
        _state.value = StewardModeState(status = status)
    }

    /**
     * AI 空闲时触发：判断任务完成情况，未完成则自动发送下一步指令。
     *
     * 仅在已开启且未处于判断中时执行（防重入）；达到循环上限后直接关闭，不再发送。
     */
    suspend fun onAiIdle(conversation: Conversation) {
        val current = _state.value
        if (!current.enabled || current.checking) return

        // 达到循环上限，关闭托管模式
        if (current.loopCount >= _maxLoops.value) {
            disable(StewardModeStatus.Stopped)
            return
        }

        _state.update { it.copy(checking = true, status = StewardModeStatus.Judging) }
        try {
            val instruction = anchorInstruction
            val report = conversation.lastAssistantReport()
            if (instruction.isNullOrBlank() || report.isBlank()) {
                disable(StewardModeStatus.Stopped)
                return
            }

            val judgement = judgeCompletion(instruction, report)
            if (judgement.completed) {
                disable(StewardModeStatus.Completed)
                return
            }

            val nextInstruction = judgement.nextInstruction
            if (nextInstruction.isNullOrBlank()) {
                disable(StewardModeStatus.Stopped)
                return
            }

            // 自动发送下一步指令
            sendMessage(nextInstruction)
            val newLoopCount = _state.value.loopCount + 1
            if (newLoopCount >= _maxLoops.value) {
                // 已达循环上限，发送本轮后关闭托管模式
                _state.value = StewardModeState(
                    loopCount = newLoopCount,
                    status = StewardModeStatus.Stopped,
                )
            } else {
                _state.update {
                    it.copy(
                        checking = false,
                        loopCount = newLoopCount,
                        status = StewardModeStatus.AutoSending,
                    )
                }
            }
        } catch (e: Exception) {
            disable(StewardModeStatus.Stopped)
        }
    }

    companion object {
        const val MIN_MAX_LOOPS = 1
        const val MAX_MAX_LOOPS = 10
        const val DEFAULT_MAX_LOOPS = 5
    }
}

/**
 * 取会话最后一条助手回复文本，作为判断依据。
 */
private fun Conversation.lastAssistantReport(): String {
    return currentMessages
        .lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.toText()
        ?.trim()
        .orEmpty()
}
