package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.agent.AcpRuntime
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.workspace.WorkspaceShellStatus
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.recall.SideEffectRecorder
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 流式 SSE delta 合并发射的时间窗口（毫秒）：高频 token 按此节流，
// 只把最新状态发射给 UI，减少每 token 一次的全量 transform 与重组
private const val STREAM_EMIT_INTERVAL_MS = 32L

// 自主执行引导：开启后注入系统提示，要求 AI 收到任务后连续调用工具直到完成，
// 不中途输出进度汇报后停止、不主动用 ask_user 打断任务流程（对标全自主执行 agent）
private const val AUTONOMOUS_EXECUTION_PROMPT = """
【自主执行模式】
当收到包含具体任务（如查询、整理、分析、修改、执行等）的请求，且当前有可用工具时，进入自主执行模式：
1. 持续调用工具推进任务，每完成一步立即评估下一步，直到任务目标全部达成。
2. 不要在任务中途输出进度汇报式文字后停止；中途不需要向用户请示或等待确认。
3. 不要用 ask_user 打断任务流程，除非用户明确要求交互，或关键信息完全缺失且无法通过其他工具自行获取。
4. 全部完成后，再输出最终结果；若任务确实无法完成，说明已尝试的步骤与失败原因，而不是停留在中间状态。
"""

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val acpRuntime: AcpRuntime? = null,
    private val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? = null,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        workspaceRoot: String? = null,
        conversationId: Uuid? = null,
        sideEffectRecorder: SideEffectRecorder? = null,
        extraSystemPrompts: List<String> = emptyList(),
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        // 多线路并发：自动探测提供同名 model 的其他 provider 线路（排除主线路与 providerOverwrite）
        // 仅对纯文本流式生成的首轮生效；工具调用循环内多线路会导致消息状态分叉，故工具场景只用主线路
        val backupRoutes: List<Pair<Provider<ProviderSetting>, ProviderSetting>> =
            if (settings.multiRouteConcurrent && model.providerOverwrite == null) {
                buildList {
                    settings.providers.forEach { setting ->
                        if (setting.id == provider.id) return@forEach
                        val hasSameModel = setting.models.any { it.modelId == model.modelId && it.type == model.type }
                        if (hasSameModel) {
                            try {
                                add(providerManager.getProviderByType(setting) to setting)
                            } catch (e: Throwable) {
                                Log.w(TAG, "multi-route: skip provider ${setting.id}", e)
                            }
                        }
                    }
                }
            } else {
                emptyList()
            }
        if (backupRoutes.isNotEmpty()) {
            Log.i(TAG, "multi-route: ${backupRoutes.size} backup route(s) for ${model.modelId}")
        }

        var messages: List<UIMessage> = messages

        // 流式视觉转换缓存：历史消息的 visualTransform 结果在生成期间保持稳定，
        // 只缓存已转换的历史部分，每次只对最后一条流式变化的消息重新转换
        var lastVisualized: List<UIMessage> = emptyList()

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant?.enableMemory == true) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    val memoryConversationId = conversationId?.toString()
                    buildMemoryTools(
                        json = json,
                        onCreation = { target, content, summary ->
                            memoryRepo.storeMemory(
                                assistantId = memoryAssistantId,
                                content = content,
                                target = target,
                                summary = summary,
                                source = "tool",
                                conversationId = memoryConversationId,
                            ).also { newMemory ->
                                sideEffectRecorder?.onMemoryCreate(newMemory, memoryAssistantId)
                            }
                        },
                        onUpdate = { id, content, summary ->
                            val before = memoryRepo.getMemoryById(id)
                            memoryRepo.updateMemory(id, content, summary = summary).also { after ->
                                if (before != null) sideEffectRecorder?.onMemoryUpdate(before, after)
                            }
                        },
                        onDelete = { id ->
                            val before = memoryRepo.getMemoryById(id)
                            memoryRepo.deleteMemory(id)
                            if (before != null) sideEffectRecorder?.onMemoryDelete(before, memoryAssistantId)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // DeepSeek V4 条件复刻：首轮工具锚定（极简工具对）+ 输出预算阶梯（dcws capSchedule）
            val deepSeekAnchor = DeepSeekAnchor.isDeepSeekModel(model.modelId) && assistant.deepSeekAnchorEnabled
            val hasToolCalls = messages.any { it.getTools().isNotEmpty() }
            val effectiveTools = if (deepSeekAnchor && !hasToolCalls && toolsInternal.isNotEmpty()) {
                val anchored = toolsInternal.filter { it.name in DeepSeekAnchor.BOOTSTRAP_TOOL_NAMES }
                if (anchored.isEmpty()) toolsInternal else anchored
            } else {
                toolsInternal
            }

            // 输出预算阶梯：首轮 1024 → 次轮 4096 → 释放。
            // reasoning 模型豁免：reasoning token 计入 max_completion_tokens，
            // 过小的预算会截断思考链，表现为生成卡顿/不流畅。
            val reasoningEnabled = model.abilities.contains(ModelAbility.REASONING) && assistant.reasoningLevel.isEnabled
            val effectiveMaxTokens = if (deepSeekAnchor && !reasoningEnabled) {
                val cap = DeepSeekAnchor.capFor(messages.count { it.role == MessageRole.USER })
                when {
                    cap == null -> assistant.maxTokens
                    assistant.maxTokens == null -> cap
                    else -> minOf(cap, assistant.maxTokens)
                }
            } else {
                assistant.maxTokens
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it
                        // 流式视觉转换优化：outputTransformers 的 transform 均为恒等实现，
                        // 无需对全历史重跑；只对最后一条流式变化的消息做 visualTransform，
                        // 历史部分复用已缓存的转换结果，避免每 token 对全历史正则扫描
                        val transformedLast = listOf(it.last()).visualTransforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        ).last()
                        val head = if (lastVisualized.size >= it.size - 1) {
                            lastVisualized.take(it.size - 1)
                        } else {
                            it.dropLast(1)
                        }
                        lastVisualized = head + transformedLast
                        emit(GenerationChunk.Messages(lastVisualized))
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = effectiveTools,
                    maxTokens = effectiveMaxTokens,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    workspaceRoot = workspaceRoot,
                    conversationId = conversationId,
                    extraSystemPrompts = extraSystemPrompts,
                    // 多线路并发仅用于无工具首轮：工具调用会让不同线路产生分叉的工具参数，
                    // 状态无法合并；已有工具调用历史（hasToolCalls）或首轮就带工具时只用主线路
                    backupRoutes = if (assistant.streamOutput &&
                        settings.multiRouteConcurrent &&
                        !messages.any { it.getTools().isNotEmpty() } &&
                        messages.count { it.role == MessageRole.USER } <= 1 &&
                        effectiveTools.isEmpty()
                    ) backupRoutes else emptyList(),
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = effectiveTools.find { it.name == tool.toolName }
                    when {
                        // 自动审批开启：需审批的工具直接放行执行（ask_user 这类需要人工输入的工具除外）
                        settings.autoApproveTools &&
                            tool.toolName != "ask_user" &&
                            toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            Log.i(TAG, "generateText: auto-approving tool ${tool.toolName}")
                            tool.copy(approvalState = ToolApprovalState.Approved)
                        }
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = effectiveTools.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            sideEffectRecorder?.onBeforeTool(toolDef.name, args)
                            val result = toolDef.execute(args)
                            sideEffectRecorder?.onAfterTool(toolDef.name, args, result)
                            val hasShellAccess = effectiveTools.any { it.name == "workspace_shell" }
                            executedTools += tool.copy(
                                output = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess)
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        append("\n${it.stackTraceToString()}")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    /**
     * 多线路竞速：并发收集多条流，首个产出元素的流接管，其余线路立即取消。
     *
     * - 竞速阶段：所有线路同时流式，谁先产出首个元素（首 token）谁接管；
     *   连接慢/失败/超时的线路在首 token 等待期被自然淘汰，健康线路不受影响（故障转移）；
     * - 赢家接管后取消其他线路，释放资源并停止备用线路的 token 消耗；
     * - 赢家产生的后续元素持续透传；
     * - 所有线路都未产出元素（全部失败）时，抛出最后记录的线路异常。
     *
     * 适用于多线路并发场景，缩短首 token 等待时间，并在首 token 阶段完成线路容错。
     */
    private fun <T> raceStreams(flows: List<Flow<T>>): Flow<T> =
        multiRouteRace(flows)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        maxTokens: Int?,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        workspaceRoot: String? = null,
        conversationId: Uuid? = null,
        backupRoutes: List<Pair<Provider<ProviderSetting>, ProviderSetting>> = emptyList(),
        extraSystemPrompts: List<String> = emptyList(),
    ) {
        val internalMessages = buildList {
            val system = buildString {
                // 全局提示词（最高优先级，置于所有系统提示最前）
                if (settings.globalPrompt.isNotBlank()) {
                    append(settings.globalPrompt)
                    appendLine()
                }
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                // 插件注入的系统提示（启用插件的 systemPrompt）
                extraSystemPrompts.forEach { prompt ->
                    if (prompt.isNotBlank()) {
                        appendLine()
                        append(prompt)
                    }
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
                // 自主执行引导：有工具可用且开关开启时，要求连续执行到底、不中途停下
                if (settings.autonomousExecutionEnabled && tools.isNotEmpty()) {
                    appendLine()
                    append(AUTONOMOUS_EXECUTION_PROMPT.trimIndent())
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        // 平台 Agent 模型：绕过 providerImpl，走 ACP 通道（agent 侧自行处理工具/上下文）
        if (model.platformAgent != null) {
            val runtime = acpRuntime ?: error("Platform agent model requires AcpRuntime")
            val root = workspaceRoot ?: resolveDefaultWorkspaceRoot()
                ?: error("Platform agent model requires a bound workspace")
            val streamChunkHandler = StreamChunkHandler(model)
            var latestMessages: List<UIMessage> = messages
            var lastEmitTime = 0L
            runtime.streamText(
                model = model,
                messages = internalMessages,
                workspaceRoot = root,
                workspaceCwd = workspaceCwd,
                conversationId = conversationId,
            ).collect { chunk ->
                latestMessages = streamChunkHandler.handle(latestMessages, chunk)
                val now = System.currentTimeMillis()
                if (now - lastEmitTime >= STREAM_EMIT_INTERVAL_MS) {
                    lastEmitTime = now
                    onUpdateMessages(latestMessages)
                }
            }
            onUpdateMessages(latestMessages)
            return
        }

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            if (backupRoutes.isEmpty()) {
                // 单线路：直接流式
                val streamChunkHandler = StreamChunkHandler(model)
                var latestMessages: List<UIMessage> = messages
                var lastEmitTime = 0L
                providerImpl.streamText(
                    providerSetting = provider,
                    messages = internalMessages,
                    params = params
                ).collect { chunk ->
                    latestMessages = streamChunkHandler.handle(latestMessages, chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= STREAM_EMIT_INTERVAL_MS) {
                        lastEmitTime = now
                        onUpdateMessages(latestMessages)
                    }
                }
                onUpdateMessages(latestMessages)
            } else {
                // 多线路并发：主线路 + 备用线路各自独立流式（独立 handler 与消息状态），
                // 竞速首个产出的线路并接管，缩短首 token 等待时间；输家线路被取消。
                // 每条线路先把 chunk 合并进自己的消息状态并节流，raceStreams 竞速"状态流"，
                // 竞速胜出的完整消息列表直接交给 UI，无需区分 chunk 来源
                val routeStateFlows = buildList {
                    fun routeStateFlow(routeStream: Flow<StreamChunk>): Flow<List<UIMessage>> = flow {
                        val handler = StreamChunkHandler(model)
                        var latest: List<UIMessage> = messages
                        var lastEmitTime = 0L
                        routeStream.collect { chunk ->
                            latest = handler.handle(latest, chunk)
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= STREAM_EMIT_INTERVAL_MS) {
                                lastEmitTime = now
                                emit(latest)
                            }
                        }
                        // 流结束发射最终状态
                        emit(latest)
                    }
                    add(routeStateFlow(providerImpl.streamText(providerSetting = provider, messages = internalMessages, params = params)))
                    backupRoutes.forEach { (backupImpl, backupSetting) ->
                        add(routeStateFlow(backupImpl.streamText(providerSetting = backupSetting, messages = internalMessages, params = params)))
                    }
                }
                raceStreams(routeStateFlows).collect { latestMessages ->
                    onUpdateMessages(latestMessages)
                }
            }
        } else {
            val result = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleTextGenerationResult(result = result, model = model)
            onUpdateMessages(messages)
        }
    }

    private suspend fun resolveDefaultWorkspaceRoot(): String? {
        val repo = workspaceRepository ?: return null
        return repo.listFlow().first()
            .firstOrNull { it.shellStatus == WorkspaceShellStatus.READY.name }
            ?.root
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (model.platformAgent != null) {
            // 平台 Agent 翻译：走 ACP 通道（agent 侧自行处理），无工作区时取默认 READY 工作区
            val runtime = acpRuntime ?: error("Platform agent model requires AcpRuntime")
            val root = resolveDefaultWorkspaceRoot()
                ?: error("Platform agent translation requires a bound workspace")
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )
            var messages = listOf(UIMessage.user(prompt))
            val streamChunkHandler = StreamChunkHandler(model)
            runtime.streamText(
                model = model,
                messages = messages,
                workspaceRoot = root,
                workspaceCwd = null,
                conversationId = null,
            ).collect { chunk ->
                messages = streamChunkHandler.handle(messages, chunk)
                val translatedText = messages.lastOrNull()?.toText() ?: ""
                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
            return@flow
        }

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""
            val streamChunkHandler = StreamChunkHandler(model)

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = streamChunkHandler.handle(messages, chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = result.message.toText()

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * 多线路竞速（内部函数，供 [GenerationHandler] 与单元测试使用）。
 *
 * 并发收集多条流，首个产出元素的流接管，其余线路立即取消：
 * - 连接慢/失败/超时的线路在首 token 等待期被自然淘汰，健康线路不受影响（故障转移）；
 * - 赢家接管后取消其他线路，释放资源并停止备用线路的 token 消耗；
 * - 赢家产生的后续元素持续透传；
 * - 所有线路都未产出元素（全部失败）时，抛出最后记录的线路异常。
 */
internal fun <T> multiRouteRace(flows: List<Flow<T>>): Flow<T> = channelFlow {
    if (flows.isEmpty()) return@channelFlow
    if (flows.size == 1) {
        flows[0].collect { send(it) }
        return@channelFlow
    }

    coroutineScope {
        // -1 表示竞速未决；>=0 表示已由第 index 条线路接管
        val winnerIndex = AtomicInteger(-1)
        val winnerJob = AtomicReference<Job?>(null)
        var lastError: Throwable? = null

        val jobs = flows.mapIndexed { index, flow ->
            launch {
                try {
                    flow.collect { element ->
                        when (winnerIndex.get()) {
                            index -> send(element)
                            -1 -> if (winnerIndex.compareAndSet(-1, index)) {
                                winnerJob.set(coroutineContext[Job])
                                send(element)
                            }
                            // 已有赢家：本线路是输家，丢弃元素
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (winnerIndex.get() == index) {
                        // 赢家中途失败：向上传播
                        throw e
                    } else {
                        lastError = e
                    }
                }
            }
        }

        // 等待赢家出现（任一线路产出首元素）或全部线路结束
        while (winnerIndex.get() < 0 && jobs.any { it.isActive }) {
            yield()
        }
        // 赢家接管后取消其余线路，停止备用线路继续生成
        winnerJob.get()?.let { win ->
            jobs.forEach { job -> if (job !== win) job.cancel() }
        }
        jobs.joinAll()

        if (winnerIndex.get() < 0) {
            lastError?.let { throw it }
                ?: throw IllegalStateException("All routes completed without producing output")
        }
    }
}
