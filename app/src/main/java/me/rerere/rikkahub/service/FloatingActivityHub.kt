package me.rerere.rikkahub.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus

/**
 * 终端命令执行记录，供悬浮球展开窗口「实时输出」标签展示。
 */
data class TerminalCommand(
    val command: String,
    val output: String,
    val isRunning: Boolean,
)

/**
 * 待办项，从 AI 输出中的 Markdown checklist（`- [ ]` / `- [x]`）解析而来。
 */
data class TodoItem(
    val text: String,
    val done: Boolean,
)

/**
 * 悬浮球展开窗口展示的 AI 活动状态快照。
 */
data class FloatingActivityState(
    val isGenerating: Boolean = false,
    val senderName: String = "",
    val status: String = "",
    val liveText: String = "",
    val reasoning: String = "",
    val todos: List<TodoItem> = emptyList(),
    val terminalCommands: List<TerminalCommand> = emptyList(),
) {
    val hasContent: Boolean
        get() = liveText.isNotBlank() || reasoning.isNotBlank() ||
            todos.isNotEmpty() || terminalCommands.isNotEmpty()
}

/**
 * 全局 AI 活动状态中心。
 *
 * 订阅 [AppEventBus] 上的聊天生成事件，把流式输出的文本、推理、终端命令与
 * checklist 待办整理成单一 [FloatingActivityState]，供悬浮球展开窗口实时展示。
 * 与 [ChatNotificationManager] 同源消费，二者互不影响。
 */
class FloatingActivityHub(
    private val appScope: AppScope,
    eventBus: AppEventBus,
    private val json: Json,
) {
    private val _state = MutableStateFlow(FloatingActivityState())
    val state: StateFlow<FloatingActivityState> = _state.asStateFlow()

    private val todoRegex = Regex(
        """(?m)^\s*(?:[-*+]|\d+[.、)])?\s*[\[［]\s*([ xX✓✔☐☑☒])\s*[\]］]\s*(.+)$"""
    )
    private val symbolTodoRegex = Regex(
        """(?m)^\s*(?:[-*+]|\d+[.、)])?\s*([☐☑☒✅])\s*(.+)$"""
    )

    init {
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationUpdate -> handleUpdate(event)
                    is AppEvent.ChatGenerationEnded -> handleEnded(event)
                    else -> {}
                }
            }
        }
    }

    private fun handleUpdate(event: AppEvent.ChatGenerationUpdate) {
        _state.value = buildState(event.lastMessage, event.senderName)
    }

    private fun handleEnded(event: AppEvent.ChatGenerationEnded) {
        _state.value = _state.value.copy(isGenerating = false, status = "")
    }

    private fun buildState(message: UIMessage, senderName: String): FloatingActivityState {
        val parts = message.parts
        val liveText = parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        val reasoning = parts.filterIsInstance<UIMessagePart.Reasoning>()
            .lastOrNull()?.reasoning.orEmpty()
        val terminalCommands = extractTerminalCommands(parts)
        val todos = extractTodos(liveText)
        return FloatingActivityState(
            isGenerating = true,
            senderName = senderName,
            status = inferStatus(parts),
            liveText = liveText,
            reasoning = reasoning,
            todos = todos,
            terminalCommands = terminalCommands,
        )
    }

    private fun inferStatus(parts: List<UIMessagePart>): String {
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()
        return when {
            lastTool != null && !lastTool.isExecuted -> "执行中"
            lastReasoning != null && lastReasoning.finishedAt == null -> "思考中"
            lastText != null -> "输出中"
            else -> "生成中"
        }
    }

    private fun extractTerminalCommands(parts: List<UIMessagePart>): List<TerminalCommand> {
        return parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolName.contains("shell", ignoreCase = true) }
            .map { tool ->
                val command = runCatching {
                    json.parseToJsonElement(tool.input).jsonObject["command"]
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: tool.input
                val output = tool.output
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { textPart ->
                        runCatching {
                            val obj = json.parseToJsonElement(textPart.text).jsonObject
                            val stdout = obj["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val stderr = obj["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
                        }.getOrNull() ?: textPart.text
                    }
                TerminalCommand(
                    command = command,
                    output = output,
                    isRunning = !tool.isExecuted,
                )
            }
    }

    private fun extractTodos(text: String): List<TodoItem> {
        if (text.isBlank()) return emptyList()
        val map = LinkedHashMap<String, TodoItem>()
        todoRegex.findAll(text).forEach { match ->
            val label = match.groupValues[2].trim()
            val done = match.groupValues[1].equals("x", ignoreCase = true) ||
                match.groupValues[1].firstOrNull()?.let { it in "✓✔☑☒" } == true
            map[label] = TodoItem(text = label, done = done)
        }
        symbolTodoRegex.findAll(text).forEach { match ->
            val label = match.groupValues[2].trim()
            map[label] = TodoItem(
                text = label,
                done = match.groupValues[1].firstOrNull()?.let { it in "☑☒✅" } == true,
            )
        }
        return map.values.toList()
    }
}
