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
import me.rerere.rikkahub.data.ai.transformers.TaskModeRouterTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
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

// 首轮工具锚定的默认核心工具集：轻量、安全、高频的能力工具。
// 首轮只暴露这些（外加用户显式白名单），首次工具调用后自动恢复全部工具，
// 既保持"极简锚定"降低首轮 schema 开销，又保证 AI 首轮就知道
// 联网搜索(search_web/scrape_web)、Linux 执行(workspace_shell)、时间/设备信息与用户澄清。
// 另含手机操控 open_app/set_volume：参数简单、无需无障碍服务即可执行（仅启动 Activity/调音量），
// 让 AI 首轮即可按用户指令打开应用或调节音量；未开启无障碍工具集时自动跳过。
private val CORE_ANCHOR_TOOL_NAMES = setOf(
    "workspace_shell",
    "search_web",
    "scrape_web",
    "get_time_info",
    "get_device_info",
    "ask_user",
    "open_app",
    "set_volume",
)

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
        conversationId: Uuid? = null,
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
                            )
                        },
                        onUpdate = { id, content, summary ->
                            memoryRepo.updateMemory(id, content, summary = summary)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // 首轮工具锚定：启用时首轮请求只保留核心工具，首次工具调用后恢复全部
            // （借鉴 dsh-anchored-standard：首轮窄工具面锚定训练对齐，工具目录扩大后能力不损）
            // 极简模式默认开启（smartToolAnchor 默认 true）；DeepSeek 家族额外自动启用
            val autoAnchor = TaskModeRouterTransformer.isDeepSeekModel(model.modelId)
            val coreNames = assistant.anchorCoreToolNames.filter { it.isNotBlank() }
            val effectiveTools = if (settings.smartStewardModeEnabled && (assistant.smartToolAnchor || autoAnchor)) {
                val hasToolCalls = messages.any { it.getTools().isNotEmpty() }
                if (hasToolCalls || toolsInternal.isEmpty()) {
                    toolsInternal
                } else {
                    // 首轮核心工具选择优先级：
                    // 1. 用户显式白名单（anchorCoreToolNames）
                    // 2. 默认核心工具集（联网搜索 + Linux 执行 + 时间/设备/澄清），
                    //    保证 AI 首轮就知道搜索工具，而不是只暴露单一工具
                    // 3. 兜底保留第一个工具
                    val anchored = when {
                        coreNames.isNotEmpty() -> toolsInternal.filter { it.name in coreNames }
                        else -> toolsInternal
                            .filter { it.name in CORE_ANCHOR_TOOL_NAMES }
                            .ifEmpty { toolsInternal.take(1) }
                    }
                    if (anchored.isEmpty()) toolsInternal.take(1) else anchored
                }
            } else {
                toolsInternal
            }

            // 双约束首轮锚定之"输出预算绳"：warmup 轮次内逐轮递增输出预算，
            // 配合 smartToolAnchor 的工具 schema 绳，让模型反复经历"极简思维 + 调工具"，
            // 把首轮锚定延伸成贯穿会话的风格惯性；warmup 结束后放开到用户上限
            // 仅用户显式开启时生效：自动启用会把 DeepSeek reasoning 模型的输出预算
            // 砍到 1024 起步，推理 token 即超限，表现为生成卡住/截断
            val effectiveMaxTokens = if (settings.smartStewardModeEnabled && assistant.smartAnchorCapLadder) {
                AnchorBudgetLadder.budgetFor(
                    userRound = messages.count { it.role == MessageRole.USER },
                    maxTokens = assistant.maxTokens,
                    base = settings.anchorBudgetBase,
                    step = settings.anchorBudgetStep,
                    warmupRounds = settings.anchorWarmupRounds,
                )
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
                            val result = toolDef.execute(args)
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
        backupRoutes: List<Pair<Provider<ProviderSetting>, ProviderSetting>> = emptyList(),
    ) {
        val internalMessages = buildList {
            val system = buildString {
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
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
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
