package me.rerere.rikkahub.data.ai.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowNode
import me.rerere.rikkahub.data.model.validate
import me.rerere.ai.provider.ProviderManager
import kotlin.uuid.Uuid

/**
 * 节点运行状态。
 */
@Serializable
enum class StepStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

/**
 * 单节点运行进度（nodeId 为图中节点 id）。
 */
@Serializable
data class RunProgress(
    val nodeId: String,
    val nodeName: String,
    val status: StepStatus,
    val output: String = "",
)

/**
 * 工作流运行结果。
 */
@Serializable
data class WorkflowRunResult(
    val workflowId: String,
    val nodes: List<RunProgress>,
    val succeeded: Boolean,
    val error: String = "",
)

/**
 * 工作流执行引擎（DAG）：波浪式并行调度 + 动态边激活。
 * - 无依赖节点并行执行；
 * - IF 节点按条件激活 true/false 分支，未命中分支下游标记 SKIPPED；
 * - MERGE 节点汇聚所有已完成源输出；
 * - FOR 节点对上游 JSON 数组逐项批量生成并聚合；
 * - 节点失败时不激活其出边（下游不可达即不执行）。
 */
class WorkflowRunner(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(
        workflow: Workflow,
        input: Map<String, String> = emptyMap(),
        onProgress: (RunProgress) -> Unit = {},
    ): WorkflowRunResult {
        val graph = workflow.effectiveGraph
        val issues = graph.validate()
        if (issues.isNotEmpty()) {
            return WorkflowRunResult(
                workflowId = workflow.id,
                nodes = emptyList(),
                succeeded = false,
                error = "工作流无效：${issues.joinToString("；")}",
            )
        }
        val nodeById = graph.nodes.associateBy { it.id }
        val incoming = graph.edges.groupBy { it.toNodeId }
        val outgoing = graph.edges.groupBy { it.fromNodeId }
        val nodeOutputs = mutableMapOf<String, String>()
        val executed = mutableSetOf<String>()
        val skipped = mutableSetOf<String>()
        val active = mutableSetOf<String>()
        val pendingMerge = mutableSetOf<String>()
        val results = mutableListOf<RunProgress>()

        fun emit(nodeId: String, status: StepStatus, output: String) {
            val p = RunProgress(nodeId, nodeById[nodeId]?.name ?: nodeId, status, output)
            results.add(p)
            onProgress(p)
        }

        val startNode = graph.nodes.find { it.type == NodeType.START }
        if (startNode != null) {
            active += startNode.id
        } else {
            active += graph.nodes.filter { incoming[it.id].orEmpty().isEmpty() }.map { it.id }
        }

        suspend fun tryActivateMerge(targetId: String) {
            if (nodeById[targetId]?.type != NodeType.MERGE) {
                active += targetId
                return
            }
            val sources = incoming[targetId].orEmpty().map { it.fromNodeId }
            if (sources.isNotEmpty() && sources.all { it in executed || it in skipped } && sources.any { it in executed }) {
                active += targetId
                pendingMerge -= targetId
            } else {
                pendingMerge += targetId
            }
        }

        fun propagateSkipped(targetId: String) {
            val node = nodeById[targetId] ?: return
            if (node.type == NodeType.MERGE) {
                // MERGE 需等待其它源；由源完成时统一触发检查
                return
            }
            if (targetId in skipped) return
            skipped += targetId
            emit(targetId, StepStatus.SKIPPED, "")
            for (edge in outgoing[targetId].orEmpty()) {
                propagateSkipped(edge.toNodeId)
            }
        }

        suspend fun executeNode(node: WorkflowNode): String {
            val config = node.config
            fun render(t: String) = TemplateRenderer.render(t, nodeOutputs, input)

            return when (config) {
                is StartStepConfig -> ""
                is EndStepConfig -> ""
                is TextStepConfig -> render(config.content)

                is AiStepConfig -> {
                    val prompt = render(config.prompt)
                    if (prompt.isBlank()) error("AI 节点的 prompt 为空")
                    runAiStep(config.assistantId, prompt)
                }

                is ShellStepConfig -> {
                    val command = render(config.command)
                    if (command.isBlank()) error("命令为空")
                    runShellStep(command, config.timeoutMillis)
                }

                is HttpStepConfig -> runHttpStep(config, nodeOutputs, input)

                is DelayStepConfig -> {
                    delay(config.seconds.coerceAtLeast(0) * 1_000L)
                    ""
                }

                is IfStepConfig -> {
                    val ok = ConditionEvaluator.eval(config.condition, nodeOutputs, input)
                    if (ok) "true" else "false"
                }

                is ForStepConfig -> runForStep(config, nodeOutputs, input)

                is MergeStepConfig -> {
                    val sources = incoming[node.id].orEmpty()
                        .map { it.fromNodeId }
                        .filter { it in executed }
                        .distinct()
                    buildString {
                        append("{")
                        sources.forEachIndexed { index, sid ->
                            if (index > 0) append(",")
                            append("\"").append(sid).append("\": ")
                            append(json.encodeToString(JsonElement.serializer(), JsonPrimitive(nodeOutputs[sid].orEmpty())))
                        }
                        append("}")
                    }
                }

                is OutputStepConfig -> render(config.template)
            }
        }

        while (true) {
            val executable = active.filter { id ->
                id !in executed && id !in skipped &&
                    incoming[id].orEmpty().all { it.fromNodeId in executed || it.fromNodeId in skipped }
            }
            if (executable.isEmpty()) {
                val mergeReady = pendingMerge.filter { id ->
                    val sources = incoming[id].orEmpty().map { it.fromNodeId }
                    sources.isNotEmpty() && sources.all { it in executed || it in skipped } && sources.any { it in executed }
                }
                if (mergeReady.isEmpty()) {
                    active.filter { it !in executed && it !in skipped }.forEach { id ->
                        emit(id, StepStatus.FAILED, "依赖未满足（上游分支未激活或已失败）")
                        executed += id
                    }
                    break
                }
                active += mergeReady
                pendingMerge -= mergeReady
                continue
            }

            val resultsPerNode = coroutineScope {
                executable.map { id ->
                    async {
                        val node = nodeById.getValue(id)
                        val outcome: Pair<String?, String?> = try {
                            executeNode(node) to null
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            null to (e.message ?: e.javaClass.simpleName)
                        }
                        id to outcome
                    }
                }.awaitAll()
            }

            for ((id, outcome) in resultsPerNode) {
                if (outcome.second != null) {
                    emit(id, StepStatus.FAILED, outcome.second!!)
                    executed += id
                    continue
                }
                val output = outcome.first ?: ""
                nodeOutputs[id] = output
                emit(id, StepStatus.SUCCESS, output)
                executed += id
                val node = nodeById.getValue(id)
                when (node.type) {
                    NodeType.IF -> {
                        val conditionTrue = output == "true"
                        for (edge in outgoing[id].orEmpty()) {
                            if (conditionTrue && edge.fromPort == "true") tryActivateMerge(edge.toNodeId)
                            if (!conditionTrue && edge.fromPort == "false") tryActivateMerge(edge.toNodeId)
                            if ((conditionTrue && edge.fromPort != "true") || (!conditionTrue && edge.fromPort != "false")) {
                                propagateSkipped(edge.toNodeId)
                            }
                        }
                    }
                    else -> {
                        for (edge in outgoing[id].orEmpty()) {
                            tryActivateMerge(edge.toNodeId)
                        }
                    }
                }
            }
        }

        val failed = results.any { it.status == StepStatus.FAILED }
        return WorkflowRunResult(
            workflowId = workflow.id,
            nodes = results,
            succeeded = !failed,
        )
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
            ?: error("未找到可用模型，请先在设置中配置")
        val providerSetting = model.findProvider(settings.providers)
            ?: error("模型未绑定可用的 Provider")
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
                    error("命令退出码 ${process.exitValue()}${errText.take(512).let { if (it.isBlank()) "" else ": $it" }}")
                }
                if (!finished) {
                    error("命令执行超时（${timeoutMillis}ms）")
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
        if (url.isBlank()) error("请求 URL 为空")
        val body = render(config.body)
        val headers = config.headers.mapValues { (_, v) -> render(v) }
        val builder = Request.Builder().url(url).method(config.method.uppercase(), body.ifBlank { null }?.toRequestBody())
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val timeout = config.timeoutMillis.coerceAtLeast(1_000)
        httpClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${bodyText.take(512)}")
            }
            bodyText
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
