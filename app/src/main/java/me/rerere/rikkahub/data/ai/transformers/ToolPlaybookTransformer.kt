package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

/** 注入标记：用于检测 playbook 是否已在本会话注入过 */
const val TOOL_PLAYBOOK_MARKER = "[workspace-tool-playbook]"

/**
 * 晋升后工具守则转换器（借鉴 dsh-win-fable-report 的 win-tool-playbook 机制）
 *
 * 与首轮工具锚定（smartToolAnchor）配合：
 * - 首轮只暴露核心工具，把模型固定在"极简思维 + 调工具"轨迹上；
 * - 首个工具调用（晋升信号）后，本转换器一次性注入 workspace 工具调用守则，
 *   降低工具调用错误（路径混淆、替换失败、不验证、重复失败命令、无视觉却截图看效果）；
 * - 守则首段为【进度汇报】：重要遗漏/思路转折/突破/关键节点时先用简洁中文汇报再继续，
 *   让用户能及时纠偏；
 * - 每会话仅注入一次：注入消息带标记前缀，后续请求检测到标记即跳过。
 */
object ToolPlaybookTransformer : InputMessageTransformer {
    const val PLAYBOOK_MARKER = TOOL_PLAYBOOK_MARKER

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (!ctx.settings.smartStewardModeEnabled) return messages
        // DeepSeek 家族模型自动启用（pro 用 anchored-standard 晋升守则）
        val autoEnabled = TaskModeRouterTransformer.isDeepSeekModel(ctx.model.modelId)
        if (!autoEnabled && !assistant.smartToolPlaybook) return messages
        return transformPlaybook(messages)
    }
}

/**
 * 晋升后注入 playbook 的核心逻辑（纯函数，可测试）
 *
 * 晋升信号与 smartToolAnchor 一致：会话中出现过带工具调用的 assistant 消息。
 * 注入位置：最近一条用户消息之后（近距离），并避开 USER -> ASSISTANT(含 Tool) 结构。
 */
internal fun transformPlaybook(messages: List<UIMessage>): List<UIMessage> {
    val hasToolCalls = messages.any { it.getTools().isNotEmpty() }
    if (!hasToolCalls) return messages

    val alreadyInjected = messages.any {
        it.role == MessageRole.USER && it.toText().contains(TOOL_PLAYBOOK_MARKER)
    }
    if (alreadyInjected) return messages

    val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (lastUserIndex < 0) return messages

    val result = messages.toMutableList()
    val insertIndex = findSafeInsertIndex(result, lastUserIndex + 1)
    result.add(insertIndex, UIMessage.user(PLAYBOOK_CONTENT))
    return result
}

internal const val PLAYBOOK_CONTENT =
    TOOL_PLAYBOOK_MARKER + "\n" +
        "【Android workspace 工具调用守则 · 本会话生效】\n\n" +
        "【进度汇报】\n" +
        "- 当某轮思考发现重要遗漏点、思路转折点、思路突破点，或任务到达关键进展节点时，" +
        "先用流畅、简洁、通俗的中文明文向用户汇报当前进度，然后再继续下一轮思考或工具调用。\n" +
        "- 只汇报关键节点，不逐条汇报常规步骤；汇报不要打断必要的连续工具操作。\n\n" +
        "【workspace 执行】\n" +
        "1. workspace_shell 每次调用都是全新进程：cd、环境变量、alias 不会跨调用保留；" +
        "需要状态就显式重建或写入文件。\n" +
        "2. 文件修改优先用 workspace_read_file / workspace_edit_file / workspace_write_file，" +
        "不要用 shell 文本命令拼装内容。\n" +
        "3. workspace_edit_file 的 old_str 必须与文件逐字符精确匹配（空格、缩进、换行）；" +
        "失败改用更短且唯一的 old_str，或回退到 workspace_write_file 整体重写。\n" +
        "4. 修改后必须验证：重新读文件确认，或运行语法检查 / 相关测试；不要跳过验证。\n" +
        "5. 报错先读完整错误与退出码，区分路径/语法错误与业务逻辑错误；" +
        "同一失败连续两次就换方法，不要重复原命令。\n" +
        "6. 输出过大会浪费 token：用受限输出（tail、过滤、只读关键片段），避免全量回显。\n" +
        "7. 前端/UI/绘图任务：语言模型没有视力，不要反复\"截图看效果\"；" +
        "代码语法检查或生成流程通过后直接交付产物与运行方式并结束任务。\n\n" +
        "【资源与纪律】\n" +
        "- 避免未限定路径的全文件系统扫描或过深的目录遍历。\n" +
        "- 文档、图片、表格、PDF 等任务优先用文档解析/搜索能力，不要凭记忆猜测内容。"
