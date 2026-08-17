package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * DeepSeek V4 条件复刻锚定。
 *
 * 依据 noone89-deepseek-v4 归因与 dcws dual-constraint-warmup-standard 预设，
 * 使思维链首块稳定以 "We need…" 复数协作口吻起手，抑制 "Let me…" / "The user wants…"
 * 单数退化口吻。
 *
 * 实测结论（tokenrhythm.studio deepseek-v4-pro-0813，见 /tmp/opencode/firstblock_probe*.py）：
 * - OpenAI function calling 的 tools 参数是推理首块退化为 "The user wants…"（TU）/ "Let me…"（LM）
 *   的决定性因素：只要传 tools（2/3/8 个），无口吻约束时首块稳定 TU/LM；
 * - Beyond 前缀、预热 replay、cap 阶梯、工具数量过滤单独均无法在 function calling 下救回 WN；
 * - 决定性稳定器是 [TONE_DIRECTIVE]：在 system 注入"协作复数口吻 + 必须以 We need 起手"的
 *   明确指令，可在 function calling 下稳定保持 WN（多任务 / temp 0 / 长手册均稳健，负向对照 TU×6）。
 *
 * 机制（dcws 三阶段 + noone89 止损 + 口吻指令）：
 * 1. 口吻指令：注入 system 前缀，function calling 下唯一有效的口吻稳定器（核心）；
 * 2. Beyond 档提示词：注入 system 前缀，复刻 noone89 记录的 max 档深度推理强度；
 * 3. 预热锚定：首轮在真实用户消息前注入合成预热消息 + 预录 "We need…" 回复，
 *    把协作轨迹确定性地锚定进会话历史（零 API 调用，dcws replay 思路）；
 * 4. 工具锚定：首轮只暴露 Minimal 工具对（shell + 读 + 改），降低工具 schema 的路由干扰；
 * 5. cap 阶梯：首轮 1024 → 次轮 4096 → 释放（dcws capSchedule）；
 * 6. 口吻止损：首块非 "We need" 即视为口吻失稳，供上层提示。
 */
object DeepSeekAnchor {

    /**
     * 口吻指令（核心稳定器，验证原文，勿改措辞）。
     * 这是 function calling 工具环境下让推理首块稳定 "We need…" 的决定性因素。
     */
    const val TONE_DIRECTIVE =
        "## Reasoning voice rule (mandatory)\n" +
            "Your internal chain-of-thought must use the collaborative plural voice.\n" +
            "- Begin every reasoning block with \"We need…\" (never \"Let me…\", never \"The user wants…\").\n" +
            "- Use \"we\", \"our\", \"us\" for yourself throughout.\n" +
            "This applies even when tools are available."

    /** Beyond 档提示词（noone89 记录的 max 档服务端注入原文）。 */
    const val BEYOND_PROMPT =
        "Reasoning Effort: Beyond maximum — exhaustive, relentless, and uncompromising.\n" +
            "Do not stop reasoning until you have independently verified the solution from multiple " +
            "angles and are certain that no assumption remains unchecked and no error remains undiscovered."

    /** 完整锚定前缀：口吻指令 + Beyond 档提示词，统一注入 system 最前。 */
    const val ANCHOR_PREFIX = TONE_DIRECTIVE + "\n\n" + BEYOND_PROMPT

    /** 预热消息（dcws warmupMessage 原文）。 */
    const val WARMUP_MESSAGE =
        "I will giving you a hard question later you need prepare now ,,therefore warm up yourself " +
            "in your COT.Please thinking **Longer as possible**.List the points to remind yourself would be better."

    /** 预热轮预录思维链（dcws replay.reasoning 的 "We need…" 起手压缩版）。 */
    const val WARMUP_REASONING =
        "We need respond to the user asking to warm up. We should prepare for the hard question ahead: " +
            "restate the objective, extract every constraint, plan decomposition before acting, " +
            "verify invariants after each change, and report progress at key milestones."

    /** 预热轮预录可见回复（dcws replay.reply 压缩版）。 */
    const val WARMUP_REPLY =
        "I'm ready. I'll keep the detailed internal chain-of-thought private, but here are the working " +
            "rules I'm priming myself with: restate the objective, extract every constraint, plan before " +
            "acting, verify after every change, and report at key milestones."

    /**
     * Minimal 工具对：dcws 的 bash + str_replace_editor 在 RikkaHub 的等价映射。
     * shell 对应 bash；read_file + edit_file 合起来对应 str_replace_editor（先读后改）。
     */
    val BOOTSTRAP_TOOL_NAMES = setOf(
        "workspace_shell",
        "workspace_read_file",
        "workspace_edit_file",
    )

    /** cap 阶梯（dcws capSchedule {1: 1024, 2: 4096}，第 3 轮起释放）。 */
    fun capFor(userRound: Int): Int? = when {
        userRound <= 0 -> null
        userRound == 1 -> BOOTSTRAP_MAX_TOKENS
        userRound == 2 -> TEST_MAX_TOKENS
        else -> null
    }

    const val BOOTSTRAP_MAX_TOKENS = 1024
    const val TEST_MAX_TOKENS = 4096

    private val DEEPSEEK_MODEL_RE = Regex(
        "deepseek|deep-seek|^ds[-/_]|(^|[^a-z])seek([^a-z]|$)",
        RegexOption.IGNORE_CASE,
    )

    /** DeepSeek 家族模型检测（官方 deepseek-* 与常见自托管别名）。 */
    fun isDeepSeekModel(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        return DEEPSEEK_MODEL_RE.containsMatchIn(modelId) ||
            modelId.lowercase().startsWith("ds-") ||
            modelId.lowercase().startsWith("ds/")
    }

    /** 口吻分类。 */
    enum class Tone {
        WE_NEED,
        LETS,
        LET_ME,
        I,
        THE_USER,
        OTHER,
    }

    /** 解析思维链首块的口吻。 */
    fun toneOf(reasoning: String?): Tone {
        val trimmed = reasoning?.trim().orEmpty()
        if (trimmed.isEmpty()) return Tone.OTHER
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("we need") -> Tone.WE_NEED
            lower.startsWith("let's") || lower.startsWith("lets") -> Tone.LETS
            lower.startsWith("let me") -> Tone.LET_ME
            lower.startsWith("i ") -> Tone.I
            lower.startsWith("the user") -> Tone.THE_USER
            else -> Tone.OTHER
        }
    }

    /** 首块是否为健康的复数协作口吻（We need / Let's）。 */
    fun isCollaborative(reasoning: String?): Boolean {
        return toneOf(reasoning).let { it == Tone.WE_NEED || it == Tone.LETS }
    }

    /** 首块是否为明确坏口吻（Let me / The user）。 */
    fun isDegraded(reasoning: String?): Boolean {
        return toneOf(reasoning).let { it == Tone.LET_ME || it == Tone.THE_USER }
    }

    /**
     * 预热锚定：在第一条用户消息之前注入合成预热消息与预录 "We need…" 回复。
     * 仅首轮（尚无工具调用）调用，锚定一次即可建立协作轨迹。
     */
    fun applyWarmupAnchor(messages: List<UIMessage>): List<UIMessage> {
        val firstUserIndex = messages.indexOfFirst { it.role == MessageRole.USER }
        if (firstUserIndex < 0) return messages

        val result = messages.toMutableList()
        val replay = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(WARMUP_REASONING),
                UIMessagePart.Text(WARMUP_REPLY),
            ),
        )
        result.add(firstUserIndex, replay)
        result.add(firstUserIndex, UIMessage.user(WARMUP_MESSAGE))
        return result
    }

    /** 锚定前缀注入：口吻指令 + Beyond 档提示词，置于 system 消息最前；无 system 消息时新增一条。 */
    fun applyAnchorPrefix(messages: List<UIMessage>): List<UIMessage> {
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        if (systemIndex < 0) {
            val result = messages.toMutableList()
            result.add(0, UIMessage.system(ANCHOR_PREFIX))
            return result
        }
        val systemMessage = messages[systemIndex]
        val newParts = systemMessage.parts.map { part ->
            if (part is UIMessagePart.Text) {
                part.copy(text = ANCHOR_PREFIX + "\n\n" + part.text)
            } else {
                part
            }
        }
        return messages.toMutableList().also {
            it[systemIndex] = systemMessage.copy(parts = newParts)
        }
    }
}
