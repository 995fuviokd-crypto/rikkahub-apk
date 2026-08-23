package me.rerere.rikkahub.data.ai.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.AiStepConfig
import me.rerere.rikkahub.data.model.DelayStepConfig
import me.rerere.rikkahub.data.model.EndStepConfig
import me.rerere.rikkahub.data.model.ExtractMode
import me.rerere.rikkahub.data.model.ExtractStepConfig
import me.rerere.rikkahub.data.model.ForStepConfig
import me.rerere.rikkahub.data.model.HttpStepConfig
import me.rerere.rikkahub.data.model.IfStepConfig
import me.rerere.rikkahub.data.model.MergeStepConfig
import me.rerere.rikkahub.data.model.NodeType
import me.rerere.rikkahub.data.model.OutputStepConfig
import me.rerere.rikkahub.data.model.ShellStepConfig
import me.rerere.rikkahub.data.model.StartStepConfig
import me.rerere.rikkahub.data.model.TextStepConfig
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowEdge
import me.rerere.rikkahub.data.model.WorkflowExecutionFailureStage
import me.rerere.rikkahub.data.model.WorkflowExecutionRecord
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowLogLevel
import me.rerere.rikkahub.data.model.WorkflowNode
import me.rerere.rikkahub.data.model.WorkflowRunLogEntry
import me.rerere.rikkahub.data.model.nodeReferenceIds
import me.rerere.rikkahub.data.model.validate
import me.rerere.ai.provider.ProviderManager
import kotlin.uuid.Uuid

/**
 * 节点运行状态。
 */
@Serializable
enum class StepStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

/**
 * 单节点运行进度（nodeId 为图中节点 id）。attempt 为已发生的失败重试次数。
 */
@Serializable
data class RunProgress(
    val nodeId: String,
    val nodeName: String,
    val status: StepStatus,
    val output: String = "",
    val attempt: Int = 0,
)

/**
 * 工作流运行结果。包含逐节点进度、执行日志、失败阶段与完整执行记录。
 */
@Serializable
data class WorkflowRunResult(
    val workflowId: String,
    val nodes: List<RunProgress>,
    val succeeded: Boolean,
    val error: String = "",
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val logs: List<WorkflowRunLogEntry> = emptyList(),
    val failureStage: WorkflowExecutionFailureStage? = null,
    val failureReason: String? = null,
    val executionRecord: WorkflowExecutionRecord? = null,
)

/**
 * 依赖图：邻接表 + 入度。
 */
private data class DependencyGraph(
    val adjacencyList: Map<String, List<String>>,
    val inDegree: Map<String, Int>,
)

/**
 * 单节点执行结果。
 */
private sealed interface NodeExecutionOutcome {
    data class Succeeded(val output: String) : NodeExecutionOutcome
    data class Failed(val error: String) : NodeExecutionOutcome
}

/**
 * 执行日志收集器：统一记录 DEBUG/WARN/ERROR 日志。
 */
private class WorkflowRunLogger {
    private val entries = mutableListOf<WorkflowRunLogEntry>()

    val logs: List<WorkflowRunLogEntry> get() = entries.toList()

    fun d(message: String, nodeId: String? = null, nodeName: String? = null) {
        append(WorkflowLogLevel.DEBUG, message, nodeId, nodeName)
    }

    fun w(message: String, nodeId: String? = null, nodeName: String? = null) {
        append(WorkflowLogLevel.WARN, message, nodeId, nodeName)
    }

    fun e(message: String, nodeId: String? = null, nodeName: String? = null) {
        append(WorkflowLogLevel.ERROR, message, nodeId, nodeName)
    }

    private fun append(level: WorkflowLogLevel, message: String, nodeId: String?, nodeName: String?) {
        entries.add(
            WorkflowRunLogEntry(
                timestamp = System.currentTimeMillis(),
                level = level,
                message = message,
                nodeId = nodeId,
                nodeName = nodeName,
            )
        )
    }
}

/**
 * 工作流执行引擎：依赖图构建 + 拓扑排序串行执行 + 边条件 + 失败处理。
 * - 入口：START 节点（或所有无入边节点）作为触发入口，产出输入 JSON；
 * - 执行顺序：显式连线 + 参数引用共同构建依赖图，按拓扑序执行；
 * - 边条件：success/error/true/false/正则 决定沿边传播，任一满足的入边即可触发下游；
 * - 失败语义：节点失败不中断整体执行，其余可达节点继续；最终若存在未被错误边处理的失败则整个工作流失败；
 * - 支持失败自动重试；支持提取（EXTRACT）节点。
 */
open class WorkflowRunner(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun run(
        workflow: Workflow,
        input: Map<String, String> = emptyMap(),
        retries: Int = 0,
        retryDelayMillis: Long = 500,
        onProgress: (RunProgress) -> Unit = {},
    ): WorkflowRunResult {
        val startedAt = System.currentTimeMillis()
        val runId = Uuid.random().toString()
        val runLogger = WorkflowRunLogger()
        val nodeById = workflow.effectiveGraph.nodes.associateBy { it.id }
        val results = mutableListOf<RunProgress>()
        val nodeResults = mutableMapOf<String, NodeExecutionState>()

        fun buildResult(
            success: Boolean,
            message: String,
            failureStage: WorkflowExecutionFailureStage? = if (success) null else WorkflowExecutionFailureStage.WORKFLOW_EXECUTION,
            failureReason: String? = if (success) null else message,
        ): WorkflowRunResult {
            val finishedAt = System.currentTimeMillis()
            val record = WorkflowExecutionRecord(
                runId = runId,
                workflowId = workflow.id,
                workflowName = workflow.name,
                startedAt = startedAt,
                finishedAt = finishedAt,
                success = success,
                message = message,
                logs = runLogger.logs,
                failureStage = failureStage,
                failureReason = failureReason,
            )
            return WorkflowRunResult(
                workflowId = workflow.id,
                nodes = results,
                succeeded = success,
                error = if (success) "" else message,
                startedAt = startedAt,
                finishedAt = finishedAt,
                logs = runLogger.logs,
                failureStage = failureStage,
                failureReason = failureReason,
                executionRecord = record,
            )
        }

        fun emit(nodeId: String, status: StepStatus, output: String, attempt: Int = 0) {            val p = RunProgress(nodeId, nodeById[nodeId]?.name ?: nodeId, status, output, attempt)
            results.add(p)
            onProgress(p)
        }

        val graph = workflow.effectiveGraph
        val issues = graph.validate()
        if (issues.isNotEmpty()) {
            val reason = issues.joinToString("；")
            runLogger.e("工作流无效：$reason")
            return buildResult(
                success = false,
                message = "工作流无效：$reason",
                failureStage = WorkflowExecutionFailureStage.WORKFLOW_STARTUP,
                failureReason = reason,
            )
        }

        runLogger.d("开始执行工作流：${workflow.name} [runId=$runId]")

        // 触发入口：START 节点；无 START 时取所有无入边节点
        val startNode = graph.nodes.find { it.type == NodeType.START }
        val incomingByTarget = graph.edges.groupBy { it.toNodeId }
        val triggerNodeIds = if (startNode != null) {
            listOf(startNode.id)
        } else {
            graph.nodes.filter { incomingByTarget[it.id].orEmpty().isEmpty() }.map { it.id }
        }
        if (triggerNodeIds.isEmpty()) {
            val reason = "没有可触发的入口节点"
            runLogger.e(reason)
            return buildResult(success = false, message = reason)
        }

        // 标记触发节点为成功，输出输入 JSON（triggerPayload）
        val triggerPayload = json.encodeToString(JsonElement.serializer(), buildJsonFrom(input))
        for (id in triggerNodeIds) {
            nodeResults[id] = NodeExecutionState.Success(triggerPayload)
            emit(id, StepStatus.SUCCESS, triggerPayload)
            runLogger.d("触发入口节点：${nodeById[id]?.name ?: id}", id, nodeById[id]?.name)
        }

        try {
            // 构建依赖图（显式连线 + 参数引用）
            val dependencyGraph = buildDependencyGraph(graph)

            // 拓扑序执行所有可达节点
            val success = executeTopologicalOrder(
                triggerNodeIds = triggerNodeIds,
                graph = graph,
                dependencyGraph = dependencyGraph,
                nodeResults = nodeResults,
                input = input,
                retries = retries.coerceAtLeast(0),
                retryDelayMillis = retryDelayMillis,
                runLogger = runLogger,
                emit = ::emit,
            )

            if (!success) {
                return buildResult(
                    success = false,
                    message = "存在未处理的失败节点，工作流判定失败",
                )
            }
            runLogger.d("工作流执行完成：${workflow.name}")
            return buildResult(success = true, message = "工作流执行成功")
        } catch (e: CancellationException) {
            runLogger.w("工作流执行被取消：${workflow.name}")
            return buildResult(
                success = false,
                message = "工作流执行被取消",
                failureStage = WorkflowExecutionFailureStage.CANCELLATION,
                failureReason = e.message ?: "已取消",
            )
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            runLogger.e("工作流执行异常：$reason")
            return buildResult(success = false, message = "工作流执行异常：$reason")
        }
    }

    private fun buildJsonFrom(input: Map<String, String>): JsonElement {
        return kotlinx.serialization.json.buildJsonObject {
            input.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
    }

    /**
     * 构建依赖图：显式连线 + 参数引用依赖，含入度。
     */
    private fun buildDependencyGraph(graph: WorkflowGraph): DependencyGraph {
        val adjacencyList = mutableMapOf<String, MutableList<String>>()
        val inDegree = mutableMapOf<String, Int>()
        val nodeIds = graph.nodes.map { it.id }

        for (id in nodeIds) {
            adjacencyList[id] = mutableListOf()
            inDegree[id] = 0
        }

        fun addEdge(sourceId: String, targetId: String) {
            if (sourceId == targetId) return
            val targets = adjacencyList.getOrPut(sourceId) { mutableListOf() }
            if (targets.contains(targetId)) return
            targets.add(targetId)
            inDegree[targetId] = (inDegree[targetId] ?: 0) + 1
        }

        for (edge in graph.edges) {
            addEdge(edge.fromNodeId, edge.toNodeId)
        }

        val idSet = nodeIds.toSet()
        for (node in graph.nodes) {
            val refs = node.config.nodeReferenceIds().filter { it in idSet }
            for (ref in refs) addEdge(ref, node.id)
        }

        return DependencyGraph(adjacencyList, inDegree)
    }

    /**
     * 从触发入口出发，前向 + 反向遍历得到可达节点集合。
     */
    private fun getReachableNodeIds(startNodeIds: List<String>, adjacencyList: Map<String, List<String>>): Set<String> {
        val forwardVisited = mutableSetOf<String>()
        val forwardQueue = ArrayDeque<String>()
        for (id in startNodeIds) {
            if (forwardVisited.add(id)) forwardQueue.addLast(id)
        }
        while (forwardQueue.isNotEmpty()) {
            val current = forwardQueue.removeFirst()
            for (next in adjacencyList[current].orEmpty()) {
                if (forwardVisited.add(next)) forwardQueue.addLast(next)
            }
        }

        val reverseAdjacencyList = mutableMapOf<String, MutableList<String>>()
        for ((sourceId, targets) in adjacencyList) {
            for (targetId in targets) {
                reverseAdjacencyList.getOrPut(targetId) { mutableListOf() }.add(sourceId)
            }
        }

        val visited = forwardVisited.toMutableSet()
        val queue = ArrayDeque<String>()
        forwardVisited.forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (prev in reverseAdjacencyList[current].orEmpty()) {
                if (visited.add(prev)) queue.addLast(prev)
            }
        }
        return visited
    }

    private fun parseBooleanLike(value: String): Boolean? {
        return when (value.trim().lowercase()) {
            "true", "1", "yes", "y", "on" -> true
            "false", "0", "no", "n", "off" -> false
            else -> null
        }
    }

    /**
     * 拓扑序串行执行所有可达节点。节点失败不中断执行，最后统一判定未处理失败。
     */
    private suspend fun executeTopologicalOrder(
        triggerNodeIds: List<String>,
        graph: WorkflowGraph,
        dependencyGraph: DependencyGraph,
        nodeResults: MutableMap<String, NodeExecutionState>,
        input: Map<String, String>,
        retries: Int,
        retryDelayMillis: Long,
        runLogger: WorkflowRunLogger,
        emit: (String, StepStatus, String, Int) -> Unit,
    ): Boolean {
        val reachableNodeIds = getReachableNodeIds(triggerNodeIds, dependencyGraph.adjacencyList)
        val nodeById = graph.nodes.associateBy { it.id }
        val incomingByTarget = graph.edges.groupBy { it.toNodeId }
        val triggerIdSet = triggerNodeIds.toSet()
        val queue = ArrayDeque<String>()
        val currentInDegree = mutableMapOf<String, Int>()

        for (nodeId in reachableNodeIds) {
            if (nodeId in triggerIdSet) continue
            currentInDegree[nodeId] = 0
        }
        for ((sourceId, targets) in dependencyGraph.adjacencyList) {
            if (sourceId !in reachableNodeIds || sourceId in triggerIdSet) continue
            for (targetId in targets) {
                if (targetId !in reachableNodeIds || targetId in triggerIdSet) continue
                currentInDegree[targetId] = (currentInDegree[targetId] ?: 0) + 1
            }
        }
        for ((nodeId, degree) in currentInDegree) {
            if (degree == 0) queue.addLast(nodeId)
        }

        var hasFailure = false

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val currentNodeId = queue.removeFirst()
            if (nodeResults.containsKey(currentNodeId)) continue
            val node = nodeById[currentNodeId] ?: continue

            val incomingConnections = incomingByTarget[currentNodeId].orEmpty().filter { conn ->
                if (conn.fromNodeId !in reachableNodeIds) return@filter false
                if (nodeById[conn.fromNodeId]?.type == NodeType.START && conn.fromNodeId !in triggerIdSet) return@filter false
                true
            }

            val shouldExecute = if (incomingConnections.isEmpty()) {
                true
            } else {
                incomingConnections.any { conn ->
                    evaluateIncomingConnection(conn, nodeById, nodeResults)
                }
            }

            if (!shouldExecute) {
                val reason = "入边条件均不满足，跳过"
                nodeResults[currentNodeId] = NodeExecutionState.Skipped(reason)
                emit(currentNodeId, StepStatus.SKIPPED, "", 0)
                runLogger.d(reason, currentNodeId, node.name)
                for (nextNodeId in dependencyGraph.adjacencyList[currentNodeId].orEmpty()) {
                    if (nextNodeId !in currentInDegree) continue
                    currentInDegree[nextNodeId] = (currentInDegree[nextNodeId] ?: 0) - 1
                    if (currentInDegree[nextNodeId] == 0) queue.addLast(nextNodeId)
                }
                continue
            }

            runLogger.d("执行节点：${node.name}", currentNodeId, node.name)
            val success = runNodeWithRetry(
                node = node,
                nodeResults = nodeResults,
                input = input,
                retries = retries,
                retryDelayMillis = retryDelayMillis,
                runLogger = runLogger,
                emit = emit,
            )
            if (!success) hasFailure = true

            for (nextNodeId in dependencyGraph.adjacencyList[currentNodeId].orEmpty()) {
                if (nextNodeId !in currentInDegree) continue
                currentInDegree[nextNodeId] = (currentInDegree[nextNodeId] ?: 0) - 1
                if (currentInDegree[nextNodeId] == 0) queue.addLast(nextNodeId)
            }
        }

        if (!hasFailure) return true

        // 存在失败节点：仅当失败被错误处理边（on_error 且目标成功）覆盖时才算已处理
        val outgoingBySource = graph.edges.groupBy { it.fromNodeId }
        fun isErrorCondition(condition: String?): Boolean {
            val normalized = condition?.trim()?.lowercase().orEmpty()
            return normalized == "error" || normalized == "failed" || normalized == "on_error"
        }
        val hasUnhandledFailure = nodeResults.any { (nodeId, state) ->
            if (state !is NodeExecutionState.Failed) return@any false
            val handled = outgoingBySource[nodeId].orEmpty().any { conn ->
                isErrorCondition(conn.condition) && nodeResults[conn.toNodeId] is NodeExecutionState.Success
            }
            !handled
        }
        return !hasUnhandledFailure
    }

    /**
     * 判定单条入边是否满足执行条件（任一入边满足即执行）。
     */
    private fun evaluateIncomingConnection(
        conn: WorkflowEdge,
        nodeById: Map<String, WorkflowNode>,
        nodeResults: Map<String, NodeExecutionState>,
    ): Boolean {
        val sourceNode = nodeById[conn.fromNodeId] ?: return false
        val sourceState = nodeResults[conn.fromNodeId] ?: return false
        if (sourceState is NodeExecutionState.Skipped) return false

        // 兼容 IF 分支：条件为空时用 fromPort（true/false）作为期望布尔
        val rawCondition = conn.condition?.trim().orEmpty()
        val effectiveCondition = if (rawCondition.isBlank() && sourceNode.type == NodeType.IF) {
            conn.fromPort.takeIf { it == "true" || it == "false" } ?: "true"
        } else {
            rawCondition
        }

        val conditionKey = effectiveCondition.trim().lowercase()
        when (conditionKey) {
            "error", "failed", "on_error" -> return sourceState is NodeExecutionState.Failed
            "success", "ok", "on_success" -> return sourceState is NodeExecutionState.Success
        }
        if (effectiveCondition.isBlank()) return sourceState is NodeExecutionState.Success

        val desiredBool = when (conditionKey) {
            "true" -> true
            "false" -> false
            else -> null
        }
        val sourceResult = (sourceState as? NodeExecutionState.Success)?.output ?: return false
        if (desiredBool != null) {
            val actual = parseBooleanLike(sourceResult) ?: false
            return actual == desiredBool
        }
        return runCatching { Regex(effectiveCondition).containsMatchIn(sourceResult) }.getOrDefault(false)
    }

    private suspend fun runNodeWithRetry(
        node: WorkflowNode,
        nodeResults: MutableMap<String, NodeExecutionState>,
        input: Map<String, String>,
        retries: Int,
        retryDelayMillis: Long,
        runLogger: WorkflowRunLogger,
        emit: (String, StepStatus, String, Int) -> Unit,
    ): Boolean {
        var lastError = ""
        for (attempt in 0..retries) {
            if (attempt > 0) delay(retryDelayMillis.coerceAtLeast(0))
            nodeResults[node.id] = NodeExecutionState.Running
            emit(node.id, StepStatus.RUNNING, "", attempt)
            val outcome = executeNode(node, nodeResults, input, runLogger)
            when (outcome) {
                is NodeExecutionOutcome.Succeeded -> {
                    nodeResults[node.id] = NodeExecutionState.Success(outcome.output)
                    emit(node.id, StepStatus.SUCCESS, outcome.output, attempt)
                    return true
                }
                is NodeExecutionOutcome.Failed -> {
                    lastError = outcome.error
                    if (attempt < retries) {
                        runLogger.w("第 ${attempt + 1} 次执行失败，将重试：${outcome.error}", node.id, node.name)
                        continue
                    }
                    runLogger.e("节点执行失败：${outcome.error}", node.id, node.name)
                    nodeResults[node.id] = NodeExecutionState.Failed(outcome.error)
                    emit(node.id, StepStatus.FAILED, outcome.error, attempt)
                    return false
                }
            }
        }
        nodeResults[node.id] = NodeExecutionState.Failed(lastError)
        emit(node.id, StepStatus.FAILED, lastError, retries)
        return false
    }

    private suspend fun executeNode(
        node: WorkflowNode,
        nodeResults: Map<String, NodeExecutionState>,
        input: Map<String, String>,
        runLogger: WorkflowRunLogger,
    ): NodeExecutionOutcome {
        currentCoroutineContext().ensureActive()
        val config = node.config
        val successOutputs = nodeResults.entries
            .filter { it.value is NodeExecutionState.Success }
            .associate { (id, state) -> id to (state as NodeExecutionState.Success).output }
        val incomingSources = nodeResults.entries
            .filter { it.value is NodeExecutionState.Success }
            .map { it.key }
        fun render(t: String) = TemplateRenderer.render(t, successOutputs, input)

        return try {
            val output = when (config) {
                is StartStepConfig -> ""
                is EndStepConfig -> ""

                is TextStepConfig -> render(config.content)

                is AiStepConfig -> {
                    val prompt = render(config.prompt)
                    if (prompt.isBlank()) throw IllegalStateException("AI 节点的 prompt 为空")
                    runAiStep(config.assistantId, prompt)
                }

                is ShellStepConfig -> {
                    val command = render(config.command)
                    if (command.isBlank()) throw IllegalStateException("命令为空")
                    runShellStep(command, config.timeoutMillis)
                }

                is HttpStepConfig -> runHttpStep(config, successOutputs, input)

                is DelayStepConfig -> {
                    delay(config.seconds.coerceAtLeast(0) * 1_000L)
                    ""
                }

                is IfStepConfig -> {
                    val ok = ConditionEvaluator.eval(config.condition, successOutputs, input)
                    if (ok) "true" else "false"
                }

                is ForStepConfig -> runForStep(config, successOutputs, input)

                is ExtractStepConfig -> runExtractStep(config, successOutputs, input)

                is MergeStepConfig -> {
                    buildString {
                        append("{")
                        incomingSources.forEachIndexed { index, sid ->
                            if (index > 0) append(",")
                            append("\"").append(sid).append("\": ")
                            append(json.encodeToString(JsonElement.serializer(), JsonPrimitive(successOutputs[sid].orEmpty())))
                        }
                        append("}")
                    }
                }

                is OutputStepConfig -> render(config.template)
            }
            NodeExecutionOutcome.Succeeded(output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runLogger.e("节点异常：${e.message ?: e.javaClass.simpleName}", node.id, node.name)
            NodeExecutionOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun runForStep(
        config: ForStepConfig,
        nodeOutputs: Map<String, String>,
        input: Map<String, String>,
    ): String {
        val itemsRaw = TemplateRenderer.render(config.itemsSource, nodeOutputs, input).trim()
        val items = parseItems(itemsRaw)
        if (items.isEmpty()) return "[]"
        val results = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            val prompt = config.prompt
                .replace("{{item}}", item)
                .replace("{{index}}", index.toString())
                .let { TemplateRenderer.render(it, nodeOutputs, input) }
            results += runAiStep(config.assistantId, prompt)
        }
        return buildString {
            append("[")
            results.forEachIndexed { index, r ->
                if (index > 0) append(",")
                append(json.encodeToString(JsonElement.serializer(), JsonPrimitive(r)))
            }
            append("]")
        }
    }

    private fun parseItems(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = runCatching {
            json.parseToJsonElement(trimmed).jsonArray
        }.getOrNull()
        if (array != null) {
            return array.mapNotNull { el ->
                runCatching { el.jsonPrimitive.contentOrNull }.getOrNull() ?: el.toString()
            }
        }
        return trimmed.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    private suspend fun runAiStep(assistantId: String, prompt: String): String {
        val settings = settingsStore.settingsFlow.value
        val assistant = runCatching { Uuid.parse(assistantId) }
            .getOrNull()
            ?.let { settings.getAssistantById(it) }
        val model = settings.findModelById(assistant?.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException("未找到可用模型，请先在设置中配置")
        val providerSetting = model.findProvider(settings.providers)
            ?: throw IllegalStateException("模型未绑定可用的 Provider")
        val provider = providerManager.getProviderByType(providerSetting)
        val params = TextGenerationParams(model = model)
        val text = StringBuilder()
        provider.streamText(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user(prompt)),
            params = params,
        ).collect { chunk ->
            if (chunk is StreamChunk.TextDelta) {
                text.append(chunk.text)
            }
        }
        return text.toString()
    }

    private suspend fun runShellStep(command: String, timeoutMillis: Long): String =
        runInterruptible(Dispatchers.IO) {
            val shellPath = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
            val process = ProcessBuilder(shellPath, "-c", command)
                .redirectErrorStream(false)
                .start()
            val stdout = StreamCollector(process.inputStream)
            val stderr = StreamCollector(process.errorStream)
            try {
                val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                }
                stdout.join(1_000)
                stderr.join(1_000)
                val outText = stdout.text()
                val errText = stderr.text()
                if (finished && process.exitValue() != 0) {
                    throw IllegalStateException("命令退出码 ${process.exitValue()}${errText.take(512).let { if (it.isBlank()) "" else ": $it" }}")
                }
                if (!finished) {
                    throw IllegalStateException("命令执行超时（${timeoutMillis}ms）")
                }
                buildString {
                    if (outText.isNotBlank()) append(outText)
                    if (errText.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(errText)
                    }
                }
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                throw e
            }
        }

    private suspend fun runHttpStep(
        config: HttpStepConfig,
        nodeOutputs: Map<String, String>,
        input: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val stepOutputs = emptyMap<Int, String>()
        fun render(t: String) = TemplateRenderer.render(t, nodeOutputs, input, stepOutputs)
        val url = render(config.url)
        if (url.isBlank()) throw IllegalStateException("请求 URL 为空")
        val body = render(config.body)
        val headers = config.headers.mapValues { (_, v) -> render(v) }
        val builder = Request.Builder().url(url).method(config.method.uppercase(), body.ifBlank { null }?.toRequestBody())
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val timeout = config.timeoutMillis.coerceAtLeast(1_000)
        httpClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${bodyText.take(512)}")
            }
            bodyText
        }
    }

    private suspend fun runExtractStep(
        config: ExtractStepConfig,
        nodeOutputs: Map<String, String>,
        input: Map<String, String>,
    ): String {
        fun render(t: String) = TemplateRenderer.render(t, nodeOutputs, input)
        val source = render(config.source)
        return when (config.mode) {
            ExtractMode.REGEX -> {
                val expr = render(config.expression)
                if (expr.isBlank()) throw IllegalStateException("正则表达式为空")
                val match = Regex(expr).find(source)
                    ?: return config.defaultValue
                val group = config.group.coerceAtLeast(0)
                if (group > 0 && match.groups.size > group) match.groupValues[group] else match.value
            }

            ExtractMode.JSON -> {
                val expr = render(config.expression).trim()
                if (expr.isBlank()) throw IllegalStateException("JSON 提取表达式为空")
                extractByJsonPath(source, expr, config.defaultValue)
            }

            ExtractMode.SUB -> {
                val start = config.startIndex.coerceAtLeast(0)
                if (start >= source.length) return config.defaultValue
                val end = if (config.length > 0) {
                    (start + config.length).coerceAtMost(source.length)
                } else {
                    source.length
                }
                source.substring(start, end.coerceAtLeast(start))
            }

            ExtractMode.CONCAT -> {
                val parts = config.others.map { render(it) }
                val effective = if (parts.isEmpty()) listOf(source) else parts
                effective.joinToString(config.separator)
            }
        }
    }

    /**
     * JSON 提取：表达式形如 `data.items[0].name` 或 `$.data.name`，支持 JSONObject/JSONArray。
     */
    private fun extractByJsonPath(source: String, path: String, defaultValue: String): String {
        if (path.isBlank()) return defaultValue
        val element = runCatching { json.parseToJsonElement(source) }.getOrNull() ?: return defaultValue

        fun getChild(current: JsonElement?, name: String): JsonElement? {
            return when (current) {
                is kotlinx.serialization.json.JsonObject -> if (name.isBlank()) current else current[name]
                else -> null
            }
        }

        fun getIndex(current: JsonElement?, index: Int): JsonElement? {
            return when (current) {
                is kotlinx.serialization.json.JsonArray -> current.getOrNull(index)
                else -> null
            }
        }

        fun readIndexToken(token: String): Pair<String, List<Int>> {
            val name = token.substringBefore("[")
            val indexes = mutableListOf<Int>()
            var rest = token.substringAfter("[", missingDelimiterValue = "")
            while (rest.isNotEmpty()) {
                val idxStr = rest.substringBefore("]", missingDelimiterValue = "")
                val idx = idxStr.toIntOrNull()
                if (idx != null) indexes.add(idx)
                rest = rest.substringAfter("[", missingDelimiterValue = "")
            }
            return name to indexes
        }

        var current: JsonElement? = element
        val segments = path.removePrefix("$.").removePrefix("$").split('.').map { it.trim() }.filter { it.isNotEmpty() }
        for (seg in segments) {
            val (name, indexes) = readIndexToken(seg)
            if (name.isNotBlank()) current = getChild(current, name)
            for (idx in indexes) current = getIndex(current, idx)
            if (current == null) return defaultValue
        }

        return when (current) {
            null -> defaultValue
            else -> runCatching { current.jsonPrimitive.contentOrNull }.getOrNull() ?: current.toString()
        }
    }
}

private class StreamCollector(private val stream: InputStream) {
    private val buffer = StringBuilder()
    private val thread = Thread {
        try {
            val bytes = ByteArray(8192)
            while (true) {
                val read = stream.read(bytes)
                if (read == -1) break
                synchronized(buffer) {
                    if (buffer.length < MAX_OUTPUT_CHARS) {
                        val append = String(bytes, 0, read)
                        buffer.append(append.take(MAX_OUTPUT_CHARS - buffer.length))
                    }
                }
            }
        } catch (e: IOException) {
            // 流被强制关闭
        } finally {
            stream.close()
        }
    }.apply { isDaemon = true }

    fun text(): String = synchronized(buffer) { buffer.toString() }

    fun join(ms: Long) {
        thread.join(ms)
    }
}

private const val MAX_OUTPUT_CHARS = 128 * 1024
