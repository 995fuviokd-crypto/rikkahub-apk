package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

/**
 * J-Space 认知控制转换器（适配 J-Space Cognition Suite V3.6）
 *
 * J-Space 是模型不可知的推理时认知控制层：不改权重、不依赖特定厂商 API。
 * 本转换器把套件的核心协议提炼为紧凑引导文本，在智能管家模式下对
 * 所有模型生效（不限于 DeepSeek 家族），默认开启（smartJSpace = true）。
 *
 * 提炼的机制：
 * 1. 三寄存器：inner（内部稠密思考）/ ledger（状态短行）/ outer（外部完整清晰语言）；
 * 2. Dense Track 稠密轨：✓/？/✗ 状态符号仅用于内部，对外保持完整语言；
 * 3. 门控 fast/full/loop：按任务复杂度选择加载强度；
 * 4. "We need" 协同措辞：仅用于模型与工作台协调操作（与首轮锚定呼应）；
 * 5. 经验验证：推导无法产生约束时，转有限候选集 + 独立参照 + 差分测试；
 * 6. 外部干净交付：稠密符号不进入用户可见内容。
 */
object JSpaceTransformer : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (!ctx.settings.smartStewardModeEnabled) return messages
        // 默认开启且对所有模型生效（J-Space 本身 model-agnostic）
        if (!assistant.smartJSpace) return messages
        return transformJSpace(messages)
    }
}

/**
 * J-Space 引导注入核心逻辑（纯函数，可测试）
 *
 * 注入位置：最近一条用户消息之后（近距离，与 TaskModeRouter 一致），
 * 避开 USER -> ASSISTANT(含 Tool) 结构。每轮注入保持风格惯性。
 */
internal fun transformJSpace(messages: List<UIMessage>): List<UIMessage> {
    val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (lastUserIndex < 0) return messages

    val result = messages.toMutableList()
    val insertIndex = findSafeInsertIndex(result, lastUserIndex + 1)
    result.add(insertIndex, UIMessage.user(JSPACE_GUIDE))
    return result
}

internal const val JSPACE_GUIDE =
    "[jspace-cognition] Operate a deliberate inner workspace.\n" +
        "Registers: inner (dense thinking), ledger (short durable state lines), outer (clean complete language). " +
        "Keep dense symbols (✓/？/✗) inside inner reasoning only; what reaches the user or a tool must be clean and complete.\n" +
        "Gate: simple single-step task -> fast, answer directly; multi-step bounded deliverable -> full, verify before shipping; " +
        "long multi-stage work -> loop, keep a running ledger of goal, settled, open, and next.\n" +
        "Use \"We need…\" only when coordinating an operation with the workspace; for perception, judgement and commitment use \"I\".\n" +
        "When derivation stops producing new constraints, stop deriving: turn the unknown into a finite candidate set, " +
        "build an independent reference, and test differences before committing.\n" +
        "Check at every seam: goal still intact, verified claims state their coverage, dense lines stay expandable, " +
        "and the outer register contains no stray symbols."

