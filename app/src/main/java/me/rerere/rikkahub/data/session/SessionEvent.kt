package me.rerere.rikkahub.data.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.StreamChunk

/**
 * 会话结束原因（对齐 dsh TurnEndReason）。
 */
enum class TurnEndReason {
    /** 正常结束 */
    Completed,

    /** 用户取消 */
    Cancelled,

    /** 错误/异常 */
    Error,

    /** 被中断（如网络中断、切换） */
    Interrupted,
}

/** 用户消息来源（对齐 dsh UserMessage.source）。 */
enum class UserMessageSource {
    /** 直接人工提示 */
    Human,

    /** agent.inject() 注入的上下文（文件变更、AGENTS.md、skill 内容等） */
    Injected,

    /** 目标续写轮 */
    GoalContinuation,
}

/**
 * SessionEvent 词汇（对齐 dsh SessionEventMap）。
 *
 * append-only 会话日志的条目，seq 为连续序号，time 为 epoch ms。
 * 消息历史由该日志派生，不单独存储。
 */
@Serializable
sealed class SessionEvent {
    abstract val seq: Long
    abstract val time: Long

    /** 打开 turn `turn`。 */
    @Serializable
    data class TurnStart(
        override val seq: Long,
        override val time: Long,
        val turn: Int,
    ) : SessionEvent()

    /** 关闭 turn `turn`，reason 标记结束原因。 */
    @Serializable
    data class TurnEnd(
        override val seq: Long,
        override val time: Long,
        val turn: Int,
        val reason: TurnEndReason,
    ) : SessionEvent()

    /** 打开 step `step`（一次模型调用 + 其请求的工具执行）。 */
    @Serializable
    data class StepStart(
        override val seq: Long,
        override val time: Long,
        val turn: Int,
        val step: Int,
    ) : SessionEvent()

    /** 关闭 step。 */
    @Serializable
    data class StepEnd(
        override val seq: Long,
        override val time: Long,
        val turn: Int,
        val step: Int,
    ) : SessionEvent()

    /** 用户角色消息（直接提示/注入上下文/目标续写）。 */
    @Serializable
    data class UserMessage(
        override val seq: Long,
        override val time: Long,
        val content: String,
        val source: UserMessageSource = UserMessageSource.Human,
    ) : SessionEvent()

    /** 原始流式块——token 级重放保真。 */
    @Serializable
    data class AssistantChunk(
        override val seq: Long,
        override val time: Long,
        val turn: Int,
        val step: Int,
        val chunk: StreamChunk,
    ) : SessionEvent()

    /** 组装完成的助手消息（派生历史使用此事件）。 */
    @Serializable
    data class AssistantMessage(
        override val seq: Long,
        override val time: Long,
        val turn: Int,
        val step: Int,
        val content: String,
        val reasoning: String? = null,
        val usage: TokenUsage? = null,
        val interrupted: Boolean = false,
    ) : SessionEvent()

    /** 模型请求一次工具调用：name + 模型产出的原始参数 JSON 字符串（未解析）。 */
    @Serializable
    data class ToolCall(
        override val seq: Long,
        override val time: Long,
        val turn: Int,
        val step: Int,
        val callId: String,
        val name: String,
        val arguments: String,
    ) : SessionEvent()

    /** 工具执行完成后的模型可见结果。 */
    @Serializable
    data class ToolResult(
        override val seq: Long,
        override val time: Long,
        val turn: Int,
        val step: Int,
        val callId: String,
        val name: String,
        val message: String,
        val error: JsonObject? = null,
        val meta: JsonObject? = null,
    ) : SessionEvent()

    /** 请求头快照：config + 系统提示 + 工具 schema 集合。 */
    @Serializable
    data class RequestHeader(
        override val seq: Long,
        override val time: Long,
        val config: JsonObject,
        val system: String? = null,
        val tools: List<String>? = null,
        val reason: RequestHeaderReason = RequestHeaderReason.Initial,
        val startsSeries: Boolean = false,
    ) : SessionEvent()

    /** 路由/容量元数据（仅路由或容量变化时记录）。 */
    @Serializable
    data class RequestContext(
        override val seq: Long,
        override val time: Long,
        val provider: String,
        val model: String,
        val contextWindow: Int? = null,
    ) : SessionEvent()

    /** 标记构造种子结束；其后的 seq 为实时工作。 */
    @Serializable
    data class EndSeed(
        override val seq: Long,
        override val time: Long,
    ) : SessionEvent()
}

/** 请求头快照原因（对齐 dsh RequestHeaderReason）。 */
enum class RequestHeaderReason {
    /** 循环实例初始 */
    Initial,

    /** 恢复 */
    Resume,

    /** 请求配置变化 */
    Change,

    /** 显式开启新消息系列 */
    Series,
}