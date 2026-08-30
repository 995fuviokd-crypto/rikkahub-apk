package me.rerere.rikkahub.service

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.media.AudioManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.plugin.PluginHook
import me.rerere.rikkahub.service.ConversationCompressor.markedAsCompressionSummary
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.subagent.createBuiltInSubagentTools
import me.rerere.rikkahub.data.ai.plan.buildPlanTool
import me.rerere.ai.provider.AgentPlatform
import me.rerere.ai.provider.AgentSubagentConfig
import me.rerere.rikkahub.data.ai.tools.createImageGenerationTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.createWorkflowTools
import me.rerere.rikkahub.data.ai.workflow.WorkflowRunner
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompression
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkflowRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.recall.MemoryActionRecord
import me.rerere.rikkahub.data.recall.RecallMode
import me.rerere.rikkahub.data.recall.RecallRecord
import me.rerere.rikkahub.data.recall.SideEffectLog
import me.rerere.rikkahub.data.recall.SideEffectRecorder
import me.rerere.rikkahub.data.recall.WorkspaceSnapshotManager
import me.rerere.rikkahub.data.recall.computeSegmentedRecall
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.TokenEstimate
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val MAX_AUTO_COMPRESS_TRIES = 3

// DeepSeek 家族模型检测（官方 deepseek-* 与常见自托管别名）
private val DEEPSEEK_MODEL_RE = Regex(
    "deepseek|deep-seek|^ds[-/_]|(^|[^a-z])seek([^a-z]|$)",
    RegexOption.IGNORE_CASE,
)

// 撤回标记 SYSTEM 消息的内容前缀，用于识别并清理重启后残留的撤回标记
private const val RECALL_MARKER_PREFIX = "[撤回] "

// 压缩摘要输出的 token 上限兜底：实际按目标窗口比例动态放宽，仅当模型上下文未知时生效
private const val COMPRESS_MAX_OUTPUT_TOKENS = 2048

// 未注册模型的默认上下文窗口（token）：用于把百分比阈值换算为绝对 token 数
private const val DEFAULT_CONTEXT_LENGTH_TOKENS = 128_000

/**
 * 解析自动压缩的绝对阈值（token）：
 * 阈值 = 模型上下文窗口 × (Max 模式 ? 3 : 1) × 用户设定的百分比。
 *
 * - 上下文窗口优先取官方注册表 MODEL_CONTEXT_LENGTH；
 *   自定义/未注册模型回退到默认窗口，保证功能可用。
 * - Max 模式面向超长任务聚合场景：先按窗口 ×3 放大判定基准，
 *   让压缩在远超单窗容量的对话中依然留足"摘要 + 近期消息 + 输出"余量。
 */
internal fun resolveContextLength(model: Model): Int {
    return ModelRegistry.MODEL_CONTEXT_LENGTH.getData(model.modelId)
        ?: DEFAULT_CONTEXT_LENGTH_TOKENS
}

internal fun resolveAutoCompressThreshold(model: Model, settings: Settings): Int {
    val base = resolveContextLength(model).toLong() * (if (settings.autoCompressMaxMode) 3 else 1)
    val percent = settings.autoCompressContextPercent.coerceIn(1, 100)
    val threshold = base * percent / 100
    // Int 溢出保护：Max(×3)+100% 下 2M 窗口可达 6M，仍在 Int 范围内；极端值兜底截断
    return threshold.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

// 分块压缩的最大并发请求数：并发过高易触发 provider 限流导致整批失败
private const val COMPRESS_CHUNK_CONCURRENCY = 4

// 单个分块压缩失败后的重试次数（应对瞬时网络错误/限流）
private const val COMPRESS_CHUNK_RETRY_ATTEMPTS = 3

// 分块重试的基础退避间隔，按尝试次数线性递增
private const val COMPRESS_CHUNK_RETRY_DELAY_MS = 500L

// 自动压缩在同一 keepRecent 档位内的尝试次数：compressMessages 内部已有重试，
// 失败一次即降档，避免同一档位整块完整重压多次拖慢最坏路径
private const val AUTO_COMPRESS_ATTEMPTS_PER_LEVEL = 1

// 流式生成过程中持久化已生成内容的节流间隔
private const val STREAM_PERSIST_INTERVAL_MS = 800L

// 生成中自动压缩的全量 token 估算节流间隔：避免每个 SSE delta 都触发全量扫描
private const val TOKEN_ESTIMATE_INTERVAL_MS = 500L

/** 自动压缩强制介入信号：中断当前生成，触发压缩后自动续跑 */
private class AutoCompressSignal : Exception() {}

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

/** 托管模式完成度判断结果。 */
data class StewardJudgement(
    val completed: Boolean,
    val reason: String = "",
    val nextInstruction: String? = null,
)

private val DEFAULT_STEWARD_PROMPT = """
    You are a task supervisor. The user gave an instruction and the AI has given an execution report. Determine whether the user's instruction has been fully completed.

    User original instruction:
    {instruction}

    AI last execution report:
    {report}

    Return only a JSON object, with no additional content:
    {
      "completed": true or false,
      "reason": "one-sentence justification",
      "next_instruction": "the next instruction when not completed; leave as an empty string when completed"
    }
""".trimIndent()

private fun stewardJudgePrompt(anchorInstruction: String, lastAssistantReport: String, template: String): String {
    return template
        .replace("{instruction}", anchorInstruction)
        .replace("{report}", lastAssistantReport)
}

private fun parseStewardJudgement(text: String): StewardJudgement {
    // 宽松提取 JSON 对象（容忍模型输出的 markdown 代码块或前后缀文本）
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) {
        return StewardJudgement(completed = false)
    }
    return runCatching {
        val json = Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        StewardJudgement(
            completed = json["completed"]?.jsonPrimitive?.booleanOrNull ?: false,
            reason = json["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            nextInstruction = json["next_instruction"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() },
        )
    }.getOrDefault(StewardJudgement(completed = false))
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)
data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val workspaceManager: WorkspaceManager,
    private val workflowRepository: WorkflowRepository,
    private val workflowRunner: WorkflowRunner,
    private val pluginManager: me.rerere.rikkahub.data.plugin.PluginManager,
    private val genMediaRepository: GenMediaRepository,
    val subagentRunTracker: me.rerere.rikkahub.data.ai.subagent.SubagentRunTracker,
    val planTracker: me.rerere.rikkahub.data.ai.plan.PlanTracker,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 工作区文件快照管理（撤回副作用回滚依赖）
    private val workspaceSnapshotManager by lazy {
        WorkspaceSnapshotManager(context, workspaceManager)
    }

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 流式持久化节流状态：生成过程中定期落库，防止中途崩溃/被杀丢失已生成内容
    private val lastStreamPersistAt = ConcurrentHashMap<Uuid, Long>()

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    // 正在执行自动压缩的会话 id 集合：UI 据此在消息列表尾部显示"正在压缩历史对话"指示
    private val _compressingConversations = MutableStateFlow<Set<Uuid>>(emptySet())
    val compressingConversations: StateFlow<Set<Uuid>> = _compressingConversations.asStateFlow()

    // 各会话当前自动重连次数：UI 在生成指示器下方实时显示"已重连 N 次"
    private val _reconnectAttempts = MutableStateFlow<Map<Uuid, Int>>(emptyMap())
    val reconnectAttempts: StateFlow<Map<Uuid, Int>> = _reconnectAttempts.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    fun getRecallHistoryFlow(conversationId: Uuid): StateFlow<List<RecallRecord>> {
        val session = sessions[conversationId] ?: return MutableStateFlow(emptyList())
        return session.recallHistory
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch { block() }

        val generationId = Uuid.random()
        val foregroundStarted = ChatGenerationForegroundService.acquire(
            context = context,
            generationId = generationId,
            conversationId = conversationId,
        )
        return appScope.launch {
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId) // 确保 session 存在
        // 生成进行中时 session.state 由生成链实时维护，此时若用数据库旧快照覆盖，
        // 会丢失正在生成的最新消息并导致状态错乱（切后台回来时表现为"消息消失 + 重新生成"）
        if (session.isGenerating) return
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            // 崩溃/中断恢复：数据库中未完成的 assistant 消息标记完成并写回，
            // 保留已生成内容而不是丢弃
            val restored = finalizeInterruptedAssistantMessages(conversation)
            val stripped = stripRecallMarkers(restored)
            updateConversation(conversationId, stripped)
            if (stripped.messageNodes != conversation.messageNodes) {
                conversationRepo.updateConversation(stripped)
            }
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        // 新的写操作使撤回历史失效，避免恢复与后续消息分叉
        clearRecallHistory(conversationId)

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = answer,
        ) {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = applyMessageBeforeSendHook(
                    content = preprocessUserInputParts(content, assistant),
                    conversationId = conversationId,
                    enabledPlugins = settings.enabledPlugins,
                )

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    /**
     * title:afterGenerate 动态 Hook：标题生成后交由插件改写。
     * payload { conversationId, text }，返回 { text }；无插件声明或未返回时保持原标题。
     */
    private suspend fun applyTitleHook(
        title: String,
        conversationId: Uuid,
        enabledPlugins: Set<String>,
    ): String {
        if (enabledPlugins.isEmpty()) return title
        val hasHandler = enabledPlugins.any { id ->
            pluginManager.loadInfo(id)?.hooks?.any { it.name == PluginHook.TITLE_AFTER_GENERATE } == true
        }
        if (!hasHandler) return title

        val result = runCatching {
            pluginManager.dispatchHook(
                enabledPlugins = enabledPlugins,
                hook = PluginHook.TITLE_AFTER_GENERATE,
                payload = buildJsonObject {
                    put("conversationId", conversationId.toString())
                    put("text", title)
                },
            )
        }.getOrElse { e ->
            Log.w(TAG, "applyTitleHook dispatch failed", e)
            return title
        }
        return result["text"]?.jsonPrimitive?.contentOrNull ?: title
    }

    /**
     * request:beforeSend 动态 Hook：把插件注入的系统提示合并后交由插件改写。
     * 无插件声明该 hook 时原样返回；有改写时以单个提示的形式替换原列表。
     */
    private suspend fun applyRequestBeforeSendHook(
        prompts: List<String>,
        conversationId: Uuid,
        enabledPlugins: Set<String>,
    ): List<String> {
        if (prompts.isEmpty() || enabledPlugins.isEmpty()) return prompts
        val hasHandler = enabledPlugins.any { id ->
            pluginManager.loadInfo(id)?.hooks?.any { it.name == PluginHook.REQUEST_BEFORE_SEND } == true
        }
        if (!hasHandler) return prompts

        val original = prompts.joinToString(separator = "\n\n")
        val result = runCatching {
            pluginManager.dispatchHook(
                enabledPlugins = enabledPlugins,
                hook = PluginHook.REQUEST_BEFORE_SEND,
                payload = buildJsonObject {
                    put("conversationId", conversationId.toString())
                    put("systemPrompt", original)
                },
            )
        }.getOrElse { e ->
            Log.w(TAG, "applyRequestBeforeSendHook dispatch failed", e)
            return prompts
        }
        val newText = result["systemPrompt"]?.jsonPrimitive?.contentOrNull ?: return prompts
        if (newText == original || newText.isBlank()) return prompts
        return listOf(newText)
    }

    /**
     * message:beforeSend 动态 Hook：用户消息入列前交由插件改写首个 Text part。
     * 无插件声明该 hook、hook 未返回 text 或返回原文本时保持不变。
     */
    private suspend fun applyMessageBeforeSendHook(
        content: List<UIMessagePart>,
        conversationId: Uuid,
        enabledPlugins: Set<String>,
    ): List<UIMessagePart> {
        val firstTextIndex = content.indexOfFirst { it is UIMessagePart.Text }
        if (firstTextIndex < 0 || enabledPlugins.isEmpty()) return content
        val original = (content[firstTextIndex] as UIMessagePart.Text).text
        val result = runCatching {
            pluginManager.dispatchHook(
                enabledPlugins = enabledPlugins,
                hook = PluginHook.MESSAGE_BEFORE_SEND,
                payload = buildJsonObject {
                    put("conversationId", conversationId.toString())
                    put("text", original)
                },
            )
        }.getOrElse { e ->
            Log.w(TAG, "applyMessageBeforeSendHook dispatch failed", e)
            return content
        }
        val newText = result["text"]?.jsonPrimitive?.contentOrNull ?: return content
        if (newText == original) return content
        return content.mapIndexed { index, part ->
            if (index == firstTextIndex) (part as UIMessagePart.Text).copy(text = newText) else part
        }
    }

    /**
     * message:afterGenerate 动态 Hook：生成正常结束后改写最后一条 assistant 消息的首个 Text part。
     */
    private suspend fun applyMessageAfterGenerateHook(
        conversationId: Uuid,
        enabledPlugins: Set<String>,
    ) {
        if (enabledPlugins.isEmpty()) return
        val conversation = getConversationFlow(conversationId).value
        val lastNode = conversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        if (lastMessage.role != MessageRole.ASSISTANT) return
        val textIndex = lastMessage.parts.indexOfFirst { it is UIMessagePart.Text }
        if (textIndex < 0) return
        val original = (lastMessage.parts[textIndex] as UIMessagePart.Text).text

        val result = runCatching {
            pluginManager.dispatchHook(
                enabledPlugins = enabledPlugins,
                hook = PluginHook.MESSAGE_AFTER_GENERATE,
                payload = buildJsonObject {
                    put("conversationId", conversationId.toString())
                    put("text", original)
                },
            )
        }.getOrElse { e ->
            Log.w(TAG, "applyMessageAfterGenerateHook dispatch failed", e)
            return
        }
        val newText = result["text"]?.jsonPrimitive?.contentOrNull ?: return
        if (newText == original) return

        val newParts = lastMessage.parts.toMutableList()
        newParts[textIndex] = (newParts[textIndex] as UIMessagePart.Text).copy(text = newText)
        val newNode = lastNode.copy(
            messages = lastNode.messages.map {
                if (it.id == lastMessage.id) it.copy(parts = newParts) else it
            },
        )
        val newNodes = conversation.messageNodes.toMutableList().also {
            it[conversation.messageNodes.lastIndex] = newNode
        }
        saveConversation(conversationId, conversation.copy(messageNodes = newNodes))
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        // 新的写操作使撤回历史失效，避免恢复与后续消息分叉
        clearRecallHistory(conversationId)

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        var autoCompressTries = 0
        var reconnectAttempts = 0
        // 生成中自动压缩的 token 估算节流：跨重连/压缩循环保留上次估算时间
        var lastTokenEstimateTime = 0L
        // 自动压缩阈值：按当前模型上下文窗口 × 百分比（Max 模式再 ×3）解析一次，循环内复用
        val autoCompressThreshold = resolveAutoCompressThreshold(model, settings)
        // 压缩摘要目标 token：按上下文窗口的 1/8 派生，替代旧的固定值，
        // 保证摘要规模与模型容量成正比（128k → ~16k；上限 32k 防止劣质模型跑飞）
        val compressTargetTokens = (resolveContextLength(model) / 8).coerceIn(2048, 32_000)
        // 副作用记录器：贯穿本轮完整生成（含重连/压缩续跑），完成后绑定到最后的 AI 节点
        val workspaceIds = resolveWorkspaceIds(assistant, model)
        val sideEffectRecorder = SideEffectRecorder(
            context = context,
            snapshotManager = workspaceSnapshotManager,
            workspaceRoots = workspaceIds.map { it.toString() },
        )
        while (true) {
            val result = runCatching {

            // reset suggestions
            updateConversation(conversationId, getConversationFlow(conversationId).value.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // 自动压缩：生成前强制介入（统一由 AutoCompressSignal 分支执行压缩，避免重复压缩）
            if (settings.autoCompressEnabled && messageRange == null && autoCompressTries < MAX_AUTO_COMPRESS_TRIES) {
                if (TokenEstimate.estimateConversationTokens(conversation) >= autoCompressThreshold) {
                    throw AutoCompressSignal()
                }
            }

            // start generating
            val session = getOrCreateSession(conversationId)
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = buildList {
                    // 压缩摘要以临时消息注入请求头部：仅存在于本次请求，
                    // 不写入存储、不显示为用户气泡（静默压缩）
                    val compression = conversation.compression
                    if (compression != null && compression.summary.isNotBlank() && messageRange == null) {
                        add(UIMessage.user(compression.summary))
                    }
                    val activeMessages = conversation.activeMessages.let {
                        if (messageRange != null) {
                            conversation.currentMessages.subList(messageRange.start, messageRange.endInclusive + 1)
                        } else {
                            it
                        }
                    }
                    addAll(activeMessages)
                },
                assistant = assistant,
                conversationId = conversationId,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                workspaceRoot = workspaceIds.firstOrNull()?.toString(),
                sideEffectRecorder = sideEffectRecorder,
                extraSystemPrompts = applyRequestBeforeSendHook(
                    prompts = pluginManager.enabledSystemPrompts(settings.enabledPlugins),
                    conversationId = conversationId,
                    enabledPlugins = settings.enabledPlugins,
                ),
                memories = if (!assistant.enableMemory) {
                    emptyList()
                } else {
                    run {
                        val memoryAssistantId = if (assistant.useGlobalMemory) {
                            MemoryRepository.GLOBAL_MEMORY_ID
                        } else {
                            assistant.id.toString()
                        }
                        val latestQuery = conversation.currentMessages.latestUserText()
                        memoryRepository.recallMemories(
                            query = latestQuery,
                            assistantId = memoryAssistantId,
                            conversationId = conversationId.toString(),
                            limit = settings.memoryRecallLimit,
                            enableVector = assistant.enableMemoryVectorEmbedding,
                        )
                    }
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    // 内置子代理引擎：配置在平台 Agent 模型上，AUTO 引擎下 DSH 平台
                    // 交给其自带 subagent 能力，其余平台由本地 delegate_subagent 工具实现
                    val subagentConfig = model.agentSubagent
                    if (subagentConfig?.enabled == true) {
                        val engineDsh = subagentConfig.engine == AgentSubagentConfig.ENGINE_DSH ||
                            (subagentConfig.engine == AgentSubagentConfig.ENGINE_AUTO &&
                                model.platformAgent == AgentPlatform.DEEPSEEK_HARNESS)
                        if (!engineDsh) {
                            addAll(
                                createBuiltInSubagentTools(
                                    config = subagentConfig,
                                    settings = settings,
                                    generationHandler = generationHandler,
                                    assistant = assistant,
                                    parentModel = model,
                                    runTracker = subagentRunTracker,
                                )
                            )
                        }
                    }
                    if (useExternalWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(localTools.getTools(assistant.localTools))
                    // AI 全能控制：全局工具开关与助手自身的 localTools 取并集
                    addAll(
                        localTools.getTools(
                            buildList {
                                if (settings.globalToolScripts) add(LocalToolOption.Scripts)
                                if (settings.globalToolAccessibility) add(LocalToolOption.Accessibility)
                                if (settings.globalToolPowerManagement) add(LocalToolOption.PowerManagement)
                                if (settings.globalToolTermux) add(LocalToolOption.Termux)
                            }
                        )
                    )
                    addAll(
                        createImageGenerationTools(
                            settings = settings,
                            providerManager = providerManager,
                            filesManager = filesManager,
                            genMediaRepository = genMediaRepository,
                        )
                    )
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    addAll(createWorkflowTools(workflowRepository, workflowRunner))
                    addAll(createWorkspaceToolsIfReady(workspaceIds, conversation.workspaceCwd))
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
                        // 服务器名可能是中文/含空格等非字母数字字符，直接拼接会导致工具名非法而不可用；
                        // 统一安全化后再作为工具名前缀
                        val safeServerName = serverName.replace(Regex("[^a-zA-Z0-9_]"), "_")
                        add(
                            Tool(
                                name = "mcp__${safeServerName}__${tool.name}",
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                    // 内置计划工具：让模型用结构化任务清单维护长任务进度（对标 TodoWrite）
                    add(buildPlanTool(planTracker))
                },
            ).onCompletion { cause ->
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成正常结束时执行 message:afterGenerate 动态 Hook（取消/异常时不改写）
                if (cause == null) {
                    applyMessageAfterGenerateHook(conversationId, settings.enabledPlugins)
                }

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                // 自动压缩：生成中/工具执行中强制介入
                if (settings.autoCompressEnabled && autoCompressTries < MAX_AUTO_COMPRESS_TRIES && chunk is GenerationChunk.Messages) {
                    // 全量 token 估算是 O(全部消息字符) 扫描，按 TOKEN_ESTIMATE_INTERVAL_MS 节流，
                    // 避免每个 SSE delta 都触发一次全量扫描拖慢生成
                    val now = System.currentTimeMillis()
                    if (now - lastTokenEstimateTime >= TOKEN_ESTIMATE_INTERVAL_MS) {
                        lastTokenEstimateTime = now
                        val currentConversation = getConversationFlow(conversationId).value
                        if (TokenEstimate.estimateConversationTokens(currentConversation) >= autoCompressThreshold) {
                            throw AutoCompressSignal()
                        }
                    }
                }
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 流式持久化：节流落库，崩溃/杀进程后重启仍能保留已生成内容
                        persistStreamingProgress(conversationId, updatedConversation)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
            }
            if (result.exceptionOrNull() is AutoCompressSignal) {
                autoCompressTries++
                // 剔除流式触发时仍在生成（finishedAt == null）的半成品 assistant 消息，
                // 避免半截回复被压缩或当作历史残留
                rollbackIncompleteAssistantMessage(conversationId)
                val compressResult = autoCompressConversation(
                    conversationId,
                    getConversationFlow(conversationId).value,
                    settings,
                    threshold = autoCompressThreshold,
                    targetTokens = compressTargetTokens,
                )
                if (!compressResult.compressed) {
                    addError(
                        IllegalStateException(context.getString(R.string.error_title_compress_conversation)),
                        conversationId,
                        title = context.getString(R.string.error_title_compress_conversation)
                    )
                    break
                }
                // 压缩后上下文已降到阈值以下，继续正常生成
                if (compressResult.tokensAfter < autoCompressThreshold) continue
                // 仍超阈值：已达到保留下限（keepRecent=0），说明即使把所有历史压成摘要也无法降到阈值以下。
                // 此时不再中断对话，而是接受当前压缩结果继续生成，并把压缩重试次数置满，
                // 避免后续轮次反复触发无意义的重复压缩。
                if (compressResult.keepRecentAtFloor) {
                    Logging.log(
                        TAG,
                        "auto compress reached floor, continue with compressed context (tokens=${compressResult.tokensAfter}, threshold=$autoCompressThreshold)"
                    )
                    autoCompressTries = MAX_AUTO_COMPRESS_TRIES
                    continue
                }
                // 未到保留下限但仍超阈值，且已达重试上限：给出明确提示，避免静默中断
                if (autoCompressTries >= MAX_AUTO_COMPRESS_TRIES) {
                    addError(
                        IllegalStateException(context.getString(R.string.chat_page_auto_compress_still_over_threshold)),
                        conversationId,
                        title = context.getString(R.string.error_title_compress_conversation)
                    )
                    break
                }
                continue
            }
            val failure = result.exceptionOrNull()
            // 自动重连：除用户主动取消外的任何错误（网络中断、流截断、协议异常等）都立即重连，
            // 不再区分错误类型，也不再指数退避，保证信息截断或异常时第一时间续跑
            if (messageRange == null && settings.autoReconnectEnabled && shouldReconnect(failure)) {
                reconnectAttempts++
                if (reconnectAttempts <= settings.autoReconnectMaxRetries) {
                    Logging.log(TAG, "handleMessageComplete: generation interrupted (${failure?.javaClass?.simpleName}), reconnecting ($reconnectAttempts/${settings.autoReconnectMaxRetries})")
                    // 暴露到 UI：生成指示器下方显示"已重连 N 次"
                    _reconnectAttempts.value = _reconnectAttempts.value + (conversationId to reconnectAttempts)
                    // 丢弃流式中断留下的半截 assistant 消息，重连从干净的 user 消息重新生成：
                    // 若保留半截 assistant 作为最后一条消息续跑（续写），部分 provider 对
                    // "最后一条是 assistant"的续写请求会一直挂起不返回任何数据，表现为一直连接/加载、内容不输出。
                    rollbackIncompleteAssistantMessage(conversationId)
                    // 立即重连：仅加极短延迟避免空转
                    delay(100L)
                    continue
                }
            }
            result.onFailure {
                // 重连彻底失败：把残留的半截 assistant 消息标记完成并保留已生成的可见内容，
                // 避免 UI 一直停留在"生成中"，同时不丢失用户已经看到的内容
                saveConversation(
                    conversationId,
                    finalizeInterruptedAssistantMessages(getConversationFlow(conversationId).value)
                )

                // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
                appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

                it.printStackTrace()
                addError(it, conversationId, title = context.getString(R.string.error_title_generation))
                Logging.log(TAG, "handleMessageComplete: $it")
                Logging.log(TAG, it.stackTraceToString())
            }.onSuccess {
                val finalConversation = getConversationFlow(conversationId).value
                saveConversation(conversationId, finalConversation)

                // 绑定副作用 log 到本轮最后一条 AI 节点，供撤回时回滚
                val recallLog = sideEffectRecorder.buildLog()
                if (!recallLog.isEmpty) {
                    val lastAssistantNode = finalConversation.messageNodes.lastOrNull { node ->
                        node.role == MessageRole.ASSISTANT
                    }
                    if (lastAssistantNode != null) {
                        getOrCreateSession(conversationId).sideEffectLogs[lastAssistantNode.id] = recallLog
                    }
                }

                // 记忆溯源：本轮生成成功后把用户提问与最终回复写入 journal，
                // 供记忆提炼（总结沉淀）链路消费，构成记忆系统的完整闭环
                if (assistant.enableMemory) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    val journalUserText = finalConversation.currentMessages.latestUserText()
                    if (journalUserText.isNotBlank()) {
                        memoryRepository.appendJournal(memoryAssistantId, conversationId.toString(), "user", journalUserText)
                    }
                    val journalAssistantText = finalConversation.currentMessages.lastOrNull()?.toText().orEmpty()
                    if (journalAssistantText.isNotBlank()) {
                        memoryRepository.appendJournal(memoryAssistantId, conversationId.toString(), "assistant", journalAssistantText)
                    }
                }

                launchWithConversationReference(conversationId) {
                    generateTitle(conversationId, finalConversation)
                }
                launchWithConversationReference(conversationId) {
                    generateSuggestion(conversationId, finalConversation)
                }
            }
            // 本轮生成结束（成功/失败）：清除重连计数，避免下轮残留旧值
            _reconnectAttempts.value = _reconnectAttempts.value - conversationId
            break
        }
    }

    /**
     * 解析当前会话应使用的 workspace 集合：
     * 助手显式绑定的 workspace 优先；未绑定时若模型是 DeepSeek 家族，
     * 自动绑定第一个 READY 的 workspace（Linux shell 环境），
     * 让 DeepSeek 通过 Linux 发送请求，发挥其原生工具调用性能。
     */
    private suspend fun resolveWorkspaceIds(
        assistant: Assistant,
        model: Model,
    ): Set<Uuid> {
        assistant.effectiveWorkspaceIds.let { if (it.isNotEmpty()) return it }
        if (!isDeepSeekFamilyModel(model.modelId)) return emptySet()
        return workspaceRepository.listFlow().first()
            .firstOrNull { it.shellStatus == WorkspaceShellStatus.READY.name }
            ?.let { setOf(Uuid.parse(it.id)) }
            .orEmpty()
    }

    /** DeepSeek 家族模型检测（官方 deepseek-* 与常见自托管别名）。 */
    private fun isDeepSeekFamilyModel(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        val lower = modelId.lowercase()
        return DEEPSEEK_MODEL_RE.containsMatchIn(modelId) ||
            lower.startsWith("ds-") ||
            lower.startsWith("ds/")
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceIds: Set<Uuid>, cwd: String? = null): List<Tool> {
        if (workspaceIds.isEmpty()) return emptyList()
        // 第一个为主工作区（工具名无后缀、携带会话 cwd），其余附加工作区带 _2/_3 后缀
        return workspaceIds.mapIndexedNotNull { index, id ->
            val workspaceId = id.toString()
            val workspace = workspaceRepository.getById(workspaceId) ?: return@mapIndexedNotNull null
            if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
                Log.d(
                    TAG,
                    "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
                )
                return@mapIndexedNotNull null
            }
            val nameSuffix = if (index == 0) "" else "_${index + 1}"
            createWorkspaceTools(
                workspaceId,
                workspaceRepository,
                if (index == 0) cwd else null,
                nameSuffix,
            )
        }.flatten()
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                val generated = result.message.toText().trim()
                val finalTitle = applyTitleHook(generated, conversationId, settings.enabledPlugins)
                saveConversation(
                    conversationId,
                    it.copy(title = finalTitle)
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 智能托管模式判断 ----

    /**
     * 使用当前对话模型判断用户指令是否已完成，未完成则生成下一步指令。
     *
     * 走独立模型调用，不写入对话历史。
     */
    suspend fun judgeStewardCompletion(
        conversationId: Uuid,
        anchorInstruction: String,
        lastAssistantReport: String,
    ): StewardJudgement = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel()
            ?: settings.findModelById(null, fallback = settings.fastModelId)
            ?: throw IllegalStateException("No chat model available")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("No provider available for chat model")
        val providerHandler = providerManager.getProviderByType(provider)
        val result = providerHandler.generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage.user(
                    stewardJudgePrompt(
                        anchorInstruction,
                        lastAssistantReport,
                        DEFAULT_STEWARD_PROMPT,
                    )
                ),
            ),
            params = backgroundTextGenerationParams(model, reasoningLevel = ReasoningLevel.OFF),
        )
        parseStewardJudgement(result.message.toText())
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        // 只在未压缩的活跃消息范围内切分：历史消息本体保留在 messageNodes 中，
        // 已压缩内容由滚动摘要代表，避免摘要套摘要造成循环压缩。
        val allActiveMessages = conversation.activeMessages

        // Split messages into those to compress and those to keep
        val (messagesToCompress, messagesToKeep) = try {
            ConversationCompressor.splitRecent(allActiveMessages, keepRecentMessages)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages), e)
        }

        suspend fun compressMessages(content: String, instructionHint: String, outputTargetTokens: Int = targetTokens): String {
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to content,
                "target_tokens" to outputTargetTokens.toString(),
                "additional_context" to buildString {
                    if (additionalPrompt.isNotBlank()) {
                        append("Additional instructions from user: $additionalPrompt")
                    }
                    if (instructionHint.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(instructionHint)
                    }
                },
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                // 摘要输出上限与目标规模联动：targetTokens 的 1.5 倍 + 余量，
                // 避免旧固定 2048 截断大窗口模型的摘要；上限 32k 防止异常输出
                params = backgroundTextGenerationParams(model, reasoningLevel = ReasoningLevel.OFF)
                    .copy(maxTokens = (outputTargetTokens + outputTargetTokens / 2).coerceIn(COMPRESS_MAX_OUTPUT_TOKENS, 32_000)),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val previousSummary = conversation.compression?.summary.orEmpty()

        // 按压缩模型实际上下文窗口动态分配单块预算与单条截断：
        // 大窗口模型一次容纳更多历史（块数少→速度快、块间不割裂→质量高）；小窗口自动收敛避免超窗
        val compressWindow = resolveContextLength(model)
        val chunkTokenBudget = (compressWindow * 70 / 100)
            .coerceIn(ConversationCompressor.DEFAULT_TOKEN_BUDGET_PER_CHUNK, 200_000)
        val contentMaxLength = (compressWindow / 8)
            .coerceIn(ConversationCompressor.DEFAULT_MAX_CONTENT_LENGTH, 64_000)

        val compressedSummaries = coroutineScope {
            // 按 token 预算分块：避免长消息拼出几十万 token 的 prompt
            // 导致压缩请求超出模型上下文窗口而失败
            val semaphore = Semaphore(COMPRESS_CHUNK_CONCURRENCY)
            ConversationCompressor.splitChunksByTokens(messagesToCompress, chunkTokenBudget, contentMaxLength)
                .map { chunk ->
                    val content = chunk.joinToString("\n\n") {
                        ConversationCompressor.compressionText(it, maxLength = contentMaxLength)
                    }
                    // 每块摘要目标按块内消息占比缩放：多块时各块输出较短摘要，
                    // 避免每块都按全局目标输出导致摘要总量膨胀、融合压力大且信息发散
                    val chunkTarget = (targetTokens.toLong() * chunk.size / messagesToCompress.size)
                        .toInt()
                        .coerceIn(COMPRESS_MAX_OUTPUT_TOKENS, targetTokens)
                    async {
                        semaphore.withPermit {
                            retryOnTransientError {
                                compressMessages(content, "", outputTargetTokens = chunkTarget)
                            }
                        }
                    }
                }
                .awaitAll()
        }

        // 滚动式摘要：已有前情摘要时与新块摘要融合为一份连贯摘要，保证上下文单点连续；
        // 融合失败则退化为直接拼接，仍保证功能可用。
        val mergedSummary = if (previousSummary.isNotBlank()) {
            runCatching {
                compressMessages(
                    content = listOf(previousSummary, *compressedSummaries.toTypedArray())
                        .joinToString("\n\n"),
                    instructionHint = "以下是一份已有的前情摘要与本轮新增内容的分段摘要，请将其融合为一份连贯、去重的整体摘要",
                )
            }.getOrNull() ?: (listOf(previousSummary, *compressedSummaries.toTypedArray()).joinToString("\n\n"))
        } else {
            compressedSummaries.joinToString("\n\n")
        }

        // 历史消息全部保留：仅更新压缩状态（摘要 + 已压缩消息 id 集合），
        // UI 据此折叠已压缩节点并在发送时以摘要代替原文进入上下文。
        val newCompression = ConversationCompression(
            summary = mergedSummary.markedAsCompressionSummary(),
            compressedMessageIds = conversation.compression?.compressedMessageIds.orEmpty() +
                messagesToCompress.map { it.id }.toSet(),
            compressedCount = (conversation.compression?.compressedCount ?: 0) + messagesToCompress.size,
        )
        val newConversation = conversation.copy(
            compression = newCompression,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    /**
     * 对压缩请求中的瞬时错误（网络抖动/限流）做有限次退避重试；
     * 取消异常直接向上传播，保证用户停止操作即时生效。
     */
    private suspend fun <T> retryOnTransientError(block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(COMPRESS_CHUNK_RETRY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(COMPRESS_CHUNK_RETRY_DELAY_MS * attempt)
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("compression request failed")
    }

    /**
     * 自动压缩结果：compressed 是否成功；tokensAfter 压缩后的估算 token 数；
     * keepRecentAtFloor 保留消息数是否已到下限（无法继续压缩）。
     */
    private data class AutoCompressResult(
        val compressed: Boolean,
        val tokensAfter: Int,
        val keepRecentAtFloor: Boolean,
    )

    /**
     * 自动压缩执行体：估算超阈值后，保留最近 N 条消息，将更早历史压缩为摘要。
     *
     * 阈值与保留条数均按模型上下文窗口动态派生，用户只需调整百分比与 Max 模式：
     * - 初始保留条数 = 窗口相关系数（大窗口保更多近期消息，保障当前任务连贯）；
     * - 同一保留档位内先做有限次快速重试排除瞬时错误（网络抖动/限流），
     *   连续失败才按二分降低保留条数缩小输入规模，避免与输入无关的失败
     *   触发大量无效的重复 LLM 请求；
     * - 即使最终仍超阈值也视为已完成压缩，交由调用方继续生成。
     */
    private suspend fun autoCompressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        settings: Settings,
        threshold: Int,
        targetTokens: Int,
    ): AutoCompressResult {
        // UI 指示：进入"正在压缩历史对话"状态（静默执行，不产生任何用户消息）
        _compressingConversations.value = _compressingConversations.value + conversationId
        return try {
            val allMessages = conversation.activeMessages
            if (allMessages.isEmpty()) {
                return AutoCompressResult(
                    compressed = false,
                    tokensAfter = TokenEstimate.estimateConversationTokens(conversation),
                    keepRecentAtFloor = true,
                )
            }
            // 初始保留档位：普通模式 32 条；Max 模式上下文基准放大 3 倍，同步保留 96 条
            var keepRecent = if (settings.autoCompressMaxMode) 96 else 32
            keepRecent = keepRecent.coerceIn(0, allMessages.size - 1)
            var attemptsAtLevel = 0
            while (true) {
                val result = runCatching {
                    compressConversation(
                        conversationId = conversationId,
                        conversation = getConversationFlow(conversationId).value,
                        additionalPrompt = "",
                        targetTokens = targetTokens,
                        keepRecentMessages = keepRecent
                    ).getOrThrow()
                }
                if (result.isFailure) {
                    throwIfCancellation(result.exceptionOrNull())
                    // 同一保留档位内先快速重试；连续失败才降档缩小输入
                    attemptsAtLevel++
                    if (attemptsAtLevel < AUTO_COMPRESS_ATTEMPTS_PER_LEVEL) continue
                    attemptsAtLevel = 0
                    if (keepRecent > 0) {
                        keepRecent /= 2
                        continue
                    }
                    return AutoCompressResult(
                        compressed = false,
                        tokensAfter = TokenEstimate.estimateConversationTokens(getConversationFlow(conversationId).value),
                        keepRecentAtFloor = true,
                    )
                }
                attemptsAtLevel = 0
                val tokensAfter = TokenEstimate.estimateConversationTokens(getConversationFlow(conversationId).value)
                if (tokensAfter < threshold) {
                    return AutoCompressResult(
                        compressed = true,
                        tokensAfter = tokensAfter,
                        keepRecentAtFloor = keepRecent == 0,
                    )
                }
                if (keepRecent > 0) {
                    // 压缩成功但上下文仍超阈值：继续把更早的保留消息并入摘要
                    keepRecent /= 2
                    continue
                }
                // 保留条数已到 0 仍超阈值（用户阈值设置过低）：接受当前结果，由调用方继续生成
                return AutoCompressResult(
                    compressed = true,
                    tokensAfter = tokensAfter,
                    keepRecentAtFloor = true,
                )
            }
            // 理论上不可达；提供明确的 Nothing 结果以满足控制流类型
            error("unreachable: auto compress loop must return")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AutoCompressResult(
                compressed = false,
                tokensAfter = TokenEstimate.estimateConversationTokens(conversation),
                keepRecentAtFloor = true,
            )
        } finally {
            _compressingConversations.value = _compressingConversations.value - conversationId
        }
    }

    /** 取消异常必须向上传播，避免被通用兜底逻辑吞掉导致停止操作失效。 */
    private fun throwIfCancellation(error: Throwable?) {
        if (error is CancellationException) throw error
    }

    /**
     * 重连前移除流式中断留下的不完整 assistant 消息，避免把半截回复当历史：
     * 若保留半截 assistant 作为最后一条消息续跑（续写），部分 provider 对
     * "最后一条是 assistant"的续写请求会一直挂起不返回任何数据，导致一直连接/加载。
     */
    private suspend fun rollbackIncompleteAssistantMessage(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        if (shouldRollbackIncompleteAssistantMessage(conversation.currentMessages) && conversation.messageNodes.isNotEmpty()) {
            updateConversation(
                conversationId,
                conversation.copy(
                    messageNodes = conversation.messageNodes.dropLast(1),
                    updateAt = Instant.now()
                )
            )
        }
    }

    /**
     * 流式持久化：生成过程中按节流间隔把当前内容落库，
     * 保证网络断开/异常退出/闪退后已生成的消息不会丢失。
     */
    private suspend fun persistStreamingProgress(conversationId: Uuid, conversation: Conversation) {
        val now = System.currentTimeMillis()
        val last = lastStreamPersistAt[conversationId] ?: 0L
        if (now - last < STREAM_PERSIST_INTERVAL_MS) return
        lastStreamPersistAt[conversationId] = now
        if (conversationRepo.existsConversationById(conversation.id)) {
            conversationRepo.updateConversationIncremental(conversation)
        } else {
            conversationRepo.insertConversation(conversation)
        }
    }

    /**
     * 崩溃/中断恢复：把数据库中未完成（finishedAt == null）的 assistant 消息标记为完成，
     * 保留已生成的文本内容。这样重启软件后消息不会卡在"任务中"，也不会丢失。
     */
    private fun finalizeInterruptedAssistantMessages(conversation: Conversation): Conversation {
        var changed = false
        val nodes = conversation.messageNodes.map { node ->
            val messages = node.messages.map { message ->
                if (message.role == MessageRole.ASSISTANT && message.finishedAt == null) {
                    changed = true
                    message.finishReasoning().copy(
                        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                    )
                } else {
                    message
                }
            }
            if (messages == node.messages) node else node.copy(messages = messages)
        }
        return if (changed) conversation.copy(messageNodes = nodes) else conversation
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        // 生成活跃期间跳过文件删除检查：conversation.files 是计算属性，每次访问都全量
        // 遍历所有消息 parts 提取文件 URI。流式阶段每 delta 调用两次会重复扫描全量消息，
        // 长对话下严重拖慢生成。生成只增改文本、不会删除含附件的消息，等生成结束再检查。
        if (!session.isGenerating) {
            checkFilesDelete(conversation, session.state.value)
        }
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 消息撤回 / 恢复 ----

    fun canRecall(conversationId: Uuid): Boolean {
        val session = sessions[conversationId] ?: return false
        if (session.isGenerating) return false
        return session.state.value.messageNodes.any { it.role != MessageRole.SYSTEM }
    }

    fun canRedo(conversationId: Uuid): Boolean {
        val session = sessions[conversationId] ?: return false
        return session.canRedo
    }

    /**
     * 撤回最近一条消息并回滚其副作用，返回是否全部副作用成功回滚。
     */
    suspend fun recallMessage(conversationId: Uuid): Boolean {
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) {
            stopGeneration(conversationId)
        }
        val conversation = getConversationFlow(conversationId).value
        val nodes = conversation.messageNodes
        if (nodes.isEmpty()) return false

        val settings = settingsStore.settingsFlow.first()
        val lastNode = nodes.lastOrNull { it.role != MessageRole.SYSTEM } ?: return false
        val lastIndex = nodes.indexOfFirst { it.id == lastNode.id }
        val log = session.sideEffectLogs[lastNode.id] ?: SideEffectLog()

        val segmentedResult = if (settings.recallSegmented) {
            computeSegmentedRecall(lastNode, settings.recallBoundaryPunctuation)
        } else {
            null
        }

        var newNodes: List<MessageNode>
        val mode: RecallMode
        val trimmedText: String?
        if (segmentedResult != null) {
            val (trimmedNode, trimmed) = segmentedResult
            mode = RecallMode.SEGMENTED
            trimmedText = trimmed
            newNodes = nodes.mapIndexed { i, node -> if (i == lastIndex) trimmedNode else node }
        } else {
            mode = RecallMode.WHOLE
            trimmedText = null
            newNodes = nodes.filterIndexed { i, _ -> i != lastIndex }
        }

        var rollbackOk = true
        if (settings.recallRollbackEnabled) {
            rollbackOk = rollbackSideEffects(log, undo = true)
        }

        val recallMarkerNodeId: Uuid? = if (settings.recallInformedAi) {
            val markerText = if (mode == RecallMode.SEGMENTED) {
                RECALL_MARKER_PREFIX + "用户撤回了上一条消息末尾的部分内容，后续对话请忽略被撤回的片段。"
            } else {
                RECALL_MARKER_PREFIX + "用户撤回了上一条消息，后续对话请忽略该条消息的内容。"
            }
            val marker = UIMessage.system(markerText).toMessageNode()
            newNodes = newNodes + marker
            marker.id
        } else {
            null
        }

        session.pushRecallRecord(
            RecallRecord(
                node = lastNode,
                nodeIndex = lastIndex,
                sideEffects = log,
                recallMode = mode,
                informedAi = settings.recallInformedAi,
                trimmedText = trimmedText,
                recallMarkerNodeId = recallMarkerNodeId,
            )
        )

        updateConversation(conversationId, conversation.copy(messageNodes = newNodes))
        saveConversation(conversationId, getConversationFlow(conversationId).value)
        return rollbackOk
    }

    /** 恢复最近一次撤回，返回是否成功。 */
    suspend fun redoMessage(conversationId: Uuid): Boolean {
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) return false
        val record = session.popRecallRecord() ?: return false
        val conversation = getConversationFlow(conversationId).value

        var nodes = conversation.messageNodes
        if (record.recallMarkerNodeId != null) {
            nodes = nodes.filterNot { it.id == record.recallMarkerNodeId }
        }

        val newNodes = when (record.recallMode) {
            RecallMode.WHOLE -> {
                val list = nodes.toMutableList()
                list.add(record.nodeIndex.coerceIn(0, list.size), record.node)
                list
            }

            RecallMode.SEGMENTED -> nodes.map { node ->
                if (node.id == record.node.id) record.node else node
            }
        }

        if (settingsStore.settingsFlow.first().recallRollbackEnabled) {
            rollbackSideEffects(record.sideEffects, undo = false)
        }

        updateConversation(conversationId, conversation.copy(messageNodes = newNodes))
        saveConversation(conversationId, getConversationFlow(conversationId).value)
        return true
    }

    /** 新的对话写操作后清空撤回历史栈并释放快照，避免恢复与后续消息分叉。 */
    fun clearRecallHistory(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        session.sideEffectLogs.values.forEach { log ->
            log.workspaceSnapshotId?.let { workspaceSnapshotManager.release(it) }
        }
        session.clearRecallRecords()
        session.sideEffectLogs.clear()
    }

    private suspend fun rollbackSideEffects(log: SideEffectLog, undo: Boolean): Boolean {
        var ok = true

        log.workspaceSnapshotId?.let { snapshotId ->
            val success = if (undo) {
                workspaceSnapshotManager.restore(snapshotId, log.workspaceRoots)
            } else {
                workspaceSnapshotManager.redo(snapshotId, log.workspaceRoots)
            }
            if (!success) ok = false
        }

        log.memoryActions.forEach { action ->
            runCatching {
                if (undo) undoMemory(action) else redoMemory(action)
            }.onFailure { ok = false }
        }

        val clipboardText = if (undo) log.clipboardBefore else log.clipboardAfter
        if (clipboardText != null) {
            runCatching { context.writeClipboardText(clipboardText) }.onFailure { ok = false }
        }

        if (undo) {
            log.calendarEventIds.forEach { eventId ->
                runCatching { deleteCalendarEvent(eventId) }.onFailure { ok = false }
            }
        }

        val targetVolume = if (undo) log.volumeBefore else log.volumeAfter
        if (log.volumeStream != null && targetVolume != null) {
            runCatching { setStreamVolume(log.volumeStream, targetVolume) }.onFailure { ok = false }
        }

        return ok
    }

    private fun deleteCalendarEvent(eventId: Long) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.contentResolver.delete(uri, null, null)
    }

    private fun setStreamVolume(stream: Int, level: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(stream, level, 0)
    }

    private suspend fun undoMemory(action: MemoryActionRecord) {
        when (action) {
            is MemoryActionRecord.Create -> memoryRepository.deleteMemory(action.id)
            is MemoryActionRecord.Update -> memoryRepository.updateMemory(
                action.id, action.beforeContent, action.beforeSummary
            )

            is MemoryActionRecord.Delete -> memoryRepository.storeMemory(
                assistantId = action.assistantId,
                content = action.content,
                target = action.target,
                summary = action.summary,
                source = "tool",
            )
        }
    }

    private suspend fun redoMemory(action: MemoryActionRecord) {
        when (action) {
            is MemoryActionRecord.Create -> memoryRepository.storeMemory(
                assistantId = action.assistantId,
                content = action.content,
                target = action.target,
                summary = action.summary,
                source = "tool",
            )

            is MemoryActionRecord.Update -> memoryRepository.updateMemory(
                action.id, action.afterContent, action.afterSummary
            )

            is MemoryActionRecord.Delete -> memoryRepository.deleteMemory(action.id)
        }
    }

    /** 清理重启后残留的撤回标记 SYSTEM 节点（撤回栈为内存态，退出后无法恢复对应标记）。 */
    private fun stripRecallMarkers(conversation: Conversation): Conversation {
        val nodes = conversation.messageNodes.filterNot { node ->
            val message = node.currentMessage
            message.role == MessageRole.SYSTEM && message.parts.any {
                it is UIMessagePart.Text && it.text.startsWith(RECALL_MARKER_PREFIX)
            }
        }
        return if (nodes.size == conversation.messageNodes.size) {
            conversation
        } else {
            conversation.copy(messageNodes = nodes)
        }
    }

    /** 分段撤回：仅单一纯文本消息可截断尾部，否则返回 null（回退整条撤回）。 */
    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        clearRecallHistory(conversationId)
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = createForkConversation(currentConversation, copiedNodes)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        clearRecallHistory(conversationId)
        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }
}

/** 提取消息列表中最后一条用户文本消息，用于当前轮记忆召回。 */
private fun List<UIMessage>.latestUserText(): String {
    for (i in indices.reversed()) {
        val message = this[i]
        if (message.role == MessageRole.USER) {
            val text = message.parts.filterIsInstance<UIMessagePart.Text>()
                .joinToString(" ") { it.text }
                .trim()
            if (text.isNotBlank()) return text
        }
    }
    return ""
}

/**
 * 是否应立即重连。
 *
 * 用户要求：信息一截断或出现任何问题时立即重连，不管错误类型。
 * 因此除用户主动取消（CancellationException）外，任何异常都触发重连，
 * 不再需要按错误类型/HTTP 状态码分类判断。
 */
internal fun shouldReconnect(error: Throwable?): Boolean {
    if (error == null) return false
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is CancellationException) return false
        cause = cause.cause
    }
    return true
}

/**
 * 重连前是否应回滚最后一条半截 assistant 消息。
 *
 * 只要消息列表最后一条是尚未完成（[UIMessage.finishedAt] == null）的 assistant 消息，
 * 就应回滚丢弃：保留它作为最后一条消息续跑（续写）会让部分 provider 的续写请求
 * 一直挂起不返回数据，表现为一直连接/加载、内容不输出。
 */
internal fun shouldRollbackIncompleteAssistantMessage(messages: List<UIMessage>): Boolean {
    val last = messages.lastOrNull() ?: return false
    return last.role == MessageRole.ASSISTANT && last.finishedAt == null
}
