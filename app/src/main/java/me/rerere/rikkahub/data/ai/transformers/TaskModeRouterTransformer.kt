package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.RouterMode

/**
 * 任务感知思维模式路由转换器
 *
 * 适配 dsh-routing-suite（flash 用）与 dsh-anchored-standard（pro 用）机制：
 * 1. 读取任务文本，分类为 spec（规划型，含修复）/ react（执行型，含构建）/ weak（弱引导）；
 * 2. 近距离引导：在最近用户消息之后注入静态引导文本（固定措辞，保持缓存命中），
 *    而不是只依赖远距离 system prompt（实测远距离指令会衰减）；
 * 3. 按模型选 persona：Flash 家族用 neutral+classify+回顾/防跑题锚（dsh-router-standard P11 最优），
 *    Pro 家族用 spec 句+classify（P24 实测回顾/收敛锚对 Pro 有害）；
 * 4. 复杂任务深度自适应：长文本或架构类任务追加深层探索引导，简单任务快速收敛。
 */
object TaskModeRouterTransformer : InputMessageTransformer {

    private val SPEC_KEYWORDS = listOf(
        "计划", "方案", "设计", "规划", "架构", "分析", "调研", "评估", "整理", "总结",
        "审查", "学习", "比较", "修复", "排查", "报错", "维护", "迁移", "故障", "异常", "重构", "调试",
        "roadmap", "plan", "design", "analy", "review", "research", "summarize", "architect",
        "strategy", "proposal", "outline", "estimate", "compare",
        "fix", "debug", "repair", "broken", "refactor", "maintain", "migrate",
    )

    private val REACT_KEYWORDS = listOf(
        "实现", "编写", "部署", "执行", "编码", "编译", "运行", "发布", "构建", "测试", "写一段",
        "开发", "搭建",
        "implement", "write", "build", "deploy", "run", "compile", "release", "execute",
        "create", "add", "change", "develop", "generate",
    )

    /**
     * 任务分类：命中关键词计数对比，spec 更多 -> SPEC，react 更多 -> REACT，都没有 -> WEAK
     */
    fun classifyTask(text: String): RouterMode {
        val lower = text.lowercase()
        val specHits = SPEC_KEYWORDS.count { it in lower }
        val reactHits = REACT_KEYWORDS.count { it in lower }
        return when {
            specHits > reactHits -> RouterMode.SPEC
            reactHits > specHits -> RouterMode.REACT
            specHits > 0 -> RouterMode.SPEC
            reactHits > 0 -> RouterMode.REACT
            else -> RouterMode.WEAK
        }
    }

    /** Flash 家族模型：modelId 含 flash（dsh-router-standard isFlashModel） */
    fun isFlashModel(modelId: String?): Boolean {
        return modelId != null && FLASH_MODEL_RE.containsMatchIn(modelId)
    }

    /**
     * DeepSeek 家族模型检测：modelId 含 deepseek / seek / ds 前缀（官方与自托管）。
     * 命中即视为"自动启用"路由引导，无需用户手动打开 smartModeRouter 开关
     * （v4-flash-godmode-opencode-go 与 dsh-routing-suite 的 isDeepSeekModel 检测）。
     */
    fun isDeepSeekModel(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        return DEEPSEEK_MODEL_RE.containsMatchIn(modelId) ||
            modelId.lowercase().startsWith("ds-") ||
            modelId.lowercase().startsWith("ds/")
    }

    /** 复杂任务启发：长文本或架构/全局类措辞视为复杂（深度自适应 v19） */
    fun isComplexTask(text: String): Boolean {
        return text.length > COMPLEX_TASK_LENGTH_THRESHOLD || COMPLEX_RE.containsMatchIn(text)
    }

    /**
     * 生成对应模式的静态引导文本（保持固定措辞以命中提示词缓存）。
     * WEAK 模式按模型家族选 persona：
     * - Flash：neutral + classify + 回顾锚 + 防跑题锚（dsh-router-standard w7，+5.67）；
     * - Pro：spec 句 + classify（P24 实测，回顾/收敛锚反而拖累 Pro）。
     */
    fun guideFor(mode: RouterMode, isReasoningModel: Boolean, modelId: String? = null): String {
        val depth = if (isReasoningModel) {
            " Think through the approach before acting."
        } else {
            ""
        }
        return when (mode) {
            RouterMode.SPEC ->
                "[task-routing] This is a planning task. First analyze the requirements and lay out a clear step-by-step plan, then execute. " +
                    "Track what has been completed; produce the result once enough information is gathered. Do not over-explore.$depth"

            RouterMode.REACT ->
                "[task-routing] This is an execution task. Act directly to complete it and focus on the result. " +
                    "Avoid over-planning; produce the outcome as soon as the information is sufficient."

            RouterMode.WEAK ->
                if (isFlashModel(modelId)) WEAK_FLASH_GUIDE + depth else WEAK_PRO_GUIDE + depth

            RouterMode.AUTO -> ""
        }
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (!ctx.settings.smartStewardModeEnabled) return messages

        // DeepSeek 家族模型（flash / pro）自动启用路由引导，无需手动打开 smartModeRouter；
        // 其他模型仍需手动开启。
        val autoEnabled = isDeepSeekModel(ctx.model.modelId)
        if (!autoEnabled && !assistant.smartModeRouter) return messages

        val taskText = messages.firstOrNull { it.role == MessageRole.USER }?.toText()?.trim()
            ?: return messages
        if (taskText.isEmpty()) return messages

        val mode = when (assistant.routerModeOverride) {
            RouterMode.AUTO -> classifyTask(taskText)
            else -> assistant.routerModeOverride
        }
        val guide = guideFor(
            mode = mode,
            isReasoningModel = ctx.model.abilities.contains(ModelAbility.REASONING),
            modelId = ctx.model.modelId
        )
        if (guide.isEmpty()) return messages

        // "We need" 思维链锚定（借鉴 dsh-anchored-standard 的 Minimal trajectory）：
        // DeepSeek 家族自动启用路由时，追加引导推理链以 "We need…" 风格展开，
        // 让思考首行常用 "We need" 开头，聚焦协作式目标拆解；
        // 非 DeepSeek 或手动开关不追加，避免改变其他模型的既有行为。
        val finalGuide = withWeNeedAnchor(guide, autoEnabled)

        // 复杂任务深度自适应：在引导后追加深层探索指示（简单任务保持快速收敛）
        val finalGuide2 = if (mode == RouterMode.WEAK && isComplexTask(taskText)) {
            finalGuide + COMPLEX_TASK_GUIDE_SUFFIX
        } else {
            finalGuide
        }
        return injectGuide(messages = messages, guide = finalGuide2)
    }

    /**
     * 近距离注入：把引导放在最近一条用户消息之后（紧跟用户输入），
     * 并避开 USER -> ASSISTANT(含 Tool) 结构
     */
    private fun injectGuide(messages: List<UIMessage>, guide: String): List<UIMessage> {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return messages

        val result = messages.toMutableList()
        val insertIndex = findSafeInsertIndex(result, lastUserIndex + 1)
        result.add(insertIndex, UIMessage.user(guide))
        return result
    }

    private val FLASH_MODEL_RE = Regex("flash", RegexOption.IGNORE_CASE)

    /** DeepSeek 家族：官方 modelId（deepseek-*）与常见自托管 deepseek 别名 */
    private val DEEPSEEK_MODEL_RE = Regex(
        "deepseek|deep-seek|^ds[-/_]|(^|[^a-z])seek([^a-z]|$)",
        RegexOption.IGNORE_CASE
    )

    private val COMPLEX_RE = Regex(
        "(重构|架构|全面|详细|设计|系统|优化|分析|survey|overview|architecture|refactor|comprehensive|detailed|design|system|optimize|analyze)",
        RegexOption.IGNORE_CASE
    )

    private const val COMPLEX_TASK_LENGTH_THRESHOLD = 120

    /** WEAK 引导：Pro 家族（spec 句 + classify，anchored-standard/routing P24） */
    private const val WEAK_PRO_GUIDE =
        "[task-routing] Judge the task yourself: decide whether this is a build or a fix task, then adopt the matching style — " +
            "build: act directly and deliver working output; fix: inspect the current state first, then plan and repair."

    /**
     * "We need" 思维链锚定（dsh-anchored-standard Minimal trajectory）：
     * DeepSeek 家族自动启用路由时追加，引导推理链首行以 "We need…" 展开。
     * 措辞保持固定以命中提示词缓存。
     */
    private const val WE_NEED_ANCHOR_GUIDE =
        " [chain-of-thought] Start your reasoning with a single \"We need…\" line stating the shared goal, " +
            "then break it into concrete steps before acting."

    /**
     * 是否追加 "We need" 思维链锚定：仅 DeepSeek 家族自动启用路由时生效
     * （autoEnabled = isDeepSeekModel），非 DeepSeek 或手动开关不追加，
     * 避免改变其他模型的既有行为。
     */
    fun withWeNeedAnchor(guide: String, autoEnabled: Boolean): String {
        return if (autoEnabled) guide + WE_NEED_ANCHOR_GUIDE else guide
    }

    /** WEAK 引导：Flash 家族（neutral + classify + 回顾锚 + 防跑题锚，dsh-router-standard w7） */
    private const val WEAK_FLASH_GUIDE =
        "[task-routing] Judge the task yourself: classify this task as a build or a fix, then adopt the matching style — " +
            "build: act directly and deliver; fix: inspect first, then repair. " +
            "Before acting, briefly review what you have already done in this session and continue from where you left off; " +
            "do not repeat completed steps. Do not run environment checks or exhaustive scans before acting."

    /** 复杂任务深度引导（v19：信息驱动的收敛优于步数驱动） */
    private const val COMPLEX_TASK_GUIDE_SUFFIX =
        " Think deeply about the architecture, edge cases, and integration points before acting. " +
            "Do not spend reasoning on the environment or tooling. Produce the result once your information is complete."
}
