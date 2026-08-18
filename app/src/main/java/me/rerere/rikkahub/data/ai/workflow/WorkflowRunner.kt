package me.rerere.rikkahub.data.ai.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
import me.rerere.rikkahub.data.model.HttpStepConfig
import me.rerere.rikkahub.data.model.ShellStepConfig
import me.rerere.rikkahub.data.model.TextStepConfig
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.ai.provider.ProviderManager
import kotlin.uuid.Uuid

/**
 * 步骤运行状态。
 */
@Serializable
enum class StepStatus { PENDING, RUNNING, SUCCESS, FAILED }

/**
 * 单步运行进度。
 */
@Serializable
data class RunProgress(
    val stepIndex: Int,
    val stepId: String,
    val stepName: String,
    val status: StepStatus,
    val output: String = "",
)

/**
 * 工作流运行结果。
 */
@Serializable
data class WorkflowRunResult(
    val workflowId: String,
    val steps: List<RunProgress>,
    val succeeded: Boolean,
)

/**
 * 工作流执行引擎：按步骤顺序执行，支持变量模板解析与输入参数注入。
 */
class WorkflowRunner(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val httpClient: OkHttpClient,
) {
    suspend fun run(
        workflow: Workflow,
        input: Map<String, String> = emptyMap(),
        onProgress: (RunProgress) -> Unit = {},
    ): WorkflowRunResult {
        val steps = workflow.steps
        val results = mutableListOf<RunProgress>()
        for ((index, step) in steps.withIndex()) {
            val running = RunProgress(index, step.id, step.name, StepStatus.RUNNING)
            onProgress(running)
            try {
                val output = executeStep(step, results, input)
                val done = RunProgress(index, step.id, step.name, StepStatus.SUCCESS, output)
                results.add(done)
                onProgress(done)
            } catch (e: Throwable) {
                val message = if (e is CancellationException) "已取消" else (e.message ?: e.javaClass.simpleName)
                val failed = RunProgress(index, step.id, step.name, StepStatus.FAILED, message)
                results.add(failed)
                onProgress(failed)
                return WorkflowRunResult(workflowId = workflow.id, steps = results, succeeded = false)
            }
        }
        return WorkflowRunResult(workflowId = workflow.id, steps = results, succeeded = true)
    }

    private suspend fun executeStep(
        step: WorkflowStep,
        priorResults: List<RunProgress>,
        input: Map<String, String>,
    ): String {
        val stepOutputs = priorResults
            .filter { it.status == StepStatus.SUCCESS }
            .associate { it.stepIndex to it.output }

        fun render(template: String) = TemplateRenderer.render(template, stepOutputs, input)

        return when (step.config) {
            is TextStepConfig -> render(step.config.content)

            is AiStepConfig -> {
                val prompt = render(step.config.prompt)
                if (prompt.isBlank()) error("AI 步骤的 prompt 为空")
                runAiStep(step.config.assistantId, prompt)
            }

            is ShellStepConfig -> {
                val command = render(step.config.command)
                if (command.isBlank()) error("命令为空")
                runShellStep(command, step.config.timeoutMillis)
            }

            is HttpStepConfig -> runHttpStep(step.config, stepOutputs, input)

            is DelayStepConfig -> {
                val seconds = step.config.seconds.coerceAtLeast(0)
                delay(seconds * 1_000L)
                ""
            }
        }
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
        stepOutputs: Map<Int, String>,
        input: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val url = TemplateRenderer.render(config.url, stepOutputs, input)
        if (url.isBlank()) error("请求 URL 为空")
        val body = TemplateRenderer.render(config.body, stepOutputs, input)
        val headers = config.headers.mapValues { (_, v) ->
            TemplateRenderer.render(v, stepOutputs, input)
        }
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
