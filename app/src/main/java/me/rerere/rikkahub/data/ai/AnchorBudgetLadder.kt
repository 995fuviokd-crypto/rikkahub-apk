package me.rerere.rikkahub.data.ai

/**
 * 锚定预算阶梯（warmup 输出预算）
 *
 * 与首轮工具锚定（smartToolAnchor）合成"双约束首轮锚定"：
 * - 第一根绳：工具 schema（首轮只暴露核心工具，smartToolAnchor 负责）
 * - 第二根绳：输出预算（本类负责，首轮最小，逐轮阶梯递增）
 *
 * 在 warmup 轮次内，每轮用户请求都被限制在很小的输出预算内，
 * 迫使模型"极简思维 + 调工具"，把首轮双锚定延伸成贯穿会话的风格惯性；
 * warmup 结束后放开到用户配置的上限。
 */
object AnchorBudgetLadder {

    /** 默认首轮输出预算（token）：足够容纳一次工具调用参数，同时强制极简回答 */
    const val DEFAULT_BASE = 1024

    /** 默认每轮递增步长（token） */
    const val DEFAULT_STEP = 512

    /** 默认 warmup 轮数：前 N 轮受限，之后放开 */
    const val DEFAULT_WARMUP_ROUNDS = 4

    /**
     * 计算当前用户轮次应使用的输出预算。
     *
     * @param userRound 当前用户提问轮次（从 1 开始，来自会话中 USER 消息数）
     * @param maxTokens 用户配置的输出上限（null = 不限制）
     * @param base 首轮预算
     * @param step 每轮递增步长
     * @param warmupRounds warmup 轮数，超过后放开
     * @return 本轮输出预算；warmup 结束后返回 maxTokens（可能为 null）
     */
    fun budgetFor(
        userRound: Int,
        maxTokens: Int?,
        base: Int = DEFAULT_BASE,
        step: Int = DEFAULT_STEP,
        warmupRounds: Int = DEFAULT_WARMUP_ROUNDS,
    ): Int? {
        if (userRound <= 0 || userRound > warmupRounds.coerceAtLeast(0)) return maxTokens
        val cap = base.coerceAtLeast(1) + (userRound - 1) * step.coerceAtLeast(1)
        return maxTokens?.let { minOf(cap, it) } ?: cap
    }
}
