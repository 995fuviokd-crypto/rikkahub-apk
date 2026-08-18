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
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
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
import me.rerere.rikkahub.service.ConversationCompressor.markedAsCompressionSummary
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.DeepSeekAnchor
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
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
import me.rerere.rikkahub.data.ai.transformers.DeepSeekAnchorTransformer
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
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkflowRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.recall.MemoryActionRecord
import me.rerere.rikkahub.data.recall.RecallMode
import me.rerere.rikkahub.data.recall.RecallRecord
import me.rerere.rikkahub.data.recall.SideEffectLog
import me.rerere.rikkahub.data.recall.SideEffectRecorder
import me.rerere.rikkahub.data.recall.WorkspaceSnapshotManager
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

// 撤回标记 SYSTEM 消息的内容前缀，用于识别并清理重启后残留的撤回标记
private const val RECALL_MARKER_PREFIX = "[撤回] "

// 压缩摘要输出的 token 上限，避免压缩模型生成过长的总结
private const val COMPRESS_MAX_OUTPUT_TOKENS = 2048

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

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        DeepSeekAnchorTransformer,
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
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
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

        // 生成链整体在后台线程执行：流式 chunk 的 CPU 处理（消息重建、token 估算）
        // 不再占用主线程，多个对话的生成可并行推进，避免互相拖累 UI 响应。
        val job = appScope.launch(Dispatchers.Default) {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

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

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        clearRecallHistory(conversationId)

        val job = appScope.launch(Dispatchers.Default) {
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

        val job = appScope.launch(Dispatchers.Default) {
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
                if (TokenEstimate.estimateConversationTokens(conversation) >= settings.autoCompressThresholdTokens) {
                    throw AutoCompressSignal()
                }
            }

            // start generating
            val session = getOrCreateSession(conversationId)
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                conversationId = conversationId,
                sideEffectRecorder = sideEffectRecorder,
                extraSystemPrompts = pluginManager.enabledSystemPrompts(settings.enabledPlugins),
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
                    if (useExternalWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(localTools.getTools(assistant.localTools))
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
                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        add(
                            Tool(
                                name = "mcp__${serverName}__${tool.name}",
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

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
                        if (TokenEstimate.estimateConversationTokens(currentConversation) >= settings.autoCompressThresholdTokens) {
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
                    settings
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
                if (compressResult.tokensAfter < settings.autoCompressThresholdTokens) continue
                // 仍超阈值：无法再压缩（保留消息已到下限）或已达重试上限时给出明确提示，避免静默中断
                if (compressResult.keepRecentAtFloor || autoCompressTries >= MAX_AUTO_COMPRESS_TRIES) {
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
        if (!DeepSeekAnchor.isDeepSeekModel(model.modelId)) return emptySet()
        return workspaceRepository.listFlow().first()
            .firstOrNull { it.shellStatus == WorkspaceShellStatus.READY.name }
            ?.let { setOf(Uuid.parse(it.id)) }
            .orEmpty()
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
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
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
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.message.toText().trim())
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
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
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId)
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
                params = backgroundTextGenerationParams(model),
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

        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val (messagesToCompress, messagesToKeep) = try {
            ConversationCompressor.splitRecent(allMessages, keepRecentMessages)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages), e)
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { ConversationCompressor.compressionText(it, maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model, reasoningLevel = ReasoningLevel.OFF)
                    .copy(maxTokens = COMPRESS_MAX_OUTPUT_TOKENS),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            // 按 token 预算分块：避免 256 条长消息拼出几十万 token 的 prompt
            // 导致压缩请求超出模型上下文窗口而失败
            ConversationCompressor.splitChunksByTokens(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary.markedAsCompressionSummary()).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
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
     */
    private suspend fun autoCompressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        settings: Settings
    ): AutoCompressResult {
        return runCatching {
            val allMessages = conversation.currentMessages
            if (allMessages.size <= 1) {
                return AutoCompressResult(
                    compressed = false,
                    tokensAfter = TokenEstimate.estimateConversationTokens(conversation),
                    keepRecentAtFloor = true,
                )
            }
            val keepRecent = settings.autoCompressKeepRecent.coerceIn(0, allMessages.size - 1)
            compressConversation(
                conversationId = conversationId,
                conversation = conversation,
                additionalPrompt = "",
                targetTokens = settings.autoCompressThresholdTokens,
                keepRecentMessages = keepRecent
            ).getOrThrow()
            val tokensAfter = TokenEstimate.estimateConversationTokens(getConversationFlow(conversationId).value)
            AutoCompressResult(
                compressed = true,
                tokensAfter = tokensAfter,
                keepRecentAtFloor = keepRecent == 0,
            )
        }.getOrElse {
            AutoCompressResult(
                compressed = false,
                tokensAfter = TokenEstimate.estimateConversationTokens(conversation),
                keepRecentAtFloor = true,
            )
        }
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
    private fun computeSegmentedRecall(
        lastNode: MessageNode,
        boundaryPunctuation: String,
    ): Pair<MessageNode, String>? {
        if (boundaryPunctuation.isEmpty()) return null
        val message = lastNode.messages.lastOrNull() ?: return null
        if (message.parts.size != 1) return null
        val onlyPart = message.parts.single()
        if (onlyPart !is UIMessagePart.Text) return null
        val text = onlyPart.text
        val lastPunctIndex = text.indexOfLast { it in boundaryPunctuation }
        if (lastPunctIndex < 0) return null
        val kept = text.substring(0, lastPunctIndex + 1)
        if (kept.isBlank()) return null
        val trimmed = text.substring(lastPunctIndex + 1)
        if (trimmed.isBlank()) return null
        val trimmedMessage = message.copy(parts = listOf(onlyPart.copy(text = kept)))
        return lastNode.copy(messages = listOf(trimmedMessage)) to trimmed
    }

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

                generationHandler.translateText(
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

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

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
