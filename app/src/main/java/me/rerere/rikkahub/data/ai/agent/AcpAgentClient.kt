package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.agent.AcpImplementation
import me.rerere.ai.agent.AcpMessage
import me.rerere.ai.agent.AcpNewSessionParams
import me.rerere.ai.agent.AcpPromptParams
import me.rerere.ai.agent.AcpTextContent
import me.rerere.workspace.WorkspaceProcessRunner
import me.rerere.workspace.WorkspaceProcessSession

/**
 * A minimal ACP (Agent Client Protocol) client that drives a workspace-hosted agent CLI.
 *
 * The agent CLI (Codex, Claude Code, OpenCode, DeepSeek Harness, …) runs as a sub-process
 * inside the workspace PRoot environment and speaks newline-delimited JSON-RPC 2.0 over
 * stdio. This client performs the standard lifecycle:
 *
 * ```
 * initialize → session/new → session/prompt (repeat) → close
 * ```
 *
 * A single reader coroutine consumes the sub-process stdout and dispatches every message:
 * request responses are matched by `id` and delivered to the pending caller; notifications
 * (no `id`) are re-emitted on [notifications] so callers can translate streaming updates
 * with [me.rerere.ai.agent.SessionUpdateBridge].
 *
 * The underlying [WorkspaceProcessSession] is kept open across calls so conversation
 * history stays on the agent side. Multiple sessions can be opened on one process; each
 * session carries its own conversation history.
 */
class AcpAgentClient(
    private val processRunner: WorkspaceProcessRunner,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private var session: WorkspaceProcessSession? = null
    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var cliCommand: List<String> = emptyList()
    private var workspaceRoot: String = ""
    private var processCwd: String = ""
    private var sessionCwd: String = ""
    private var agentEnvironment: Map<String, String> = emptyMap()

    private val pendingResponses = mutableMapOf<Long, Channel<JsonElement>>()
    private val notificationFlow = MutableSharedFlow<JsonElement>(extraBufferCapacity = 512)

    private val notifications: Flow<JsonElement> = notificationFlow.asSharedFlow()
    private var initialized = false
    private var activeSessionId: String? = null
    private var idCounter = 0L

    /** Agent's `agentCapabilities` from the initialize result (may be null if absent). */
    @Volatile
    var agentCapabilities: JsonElement? = null
        private set

    /**
     * Connects to the agent: starts the sub-process (using [cliCommand]/[cwd]/[extraEnv])
     * and negotiates the protocol version. Already-connected clients are left untouched;
     * reconnect with a different CLI/environment tears down and restarts the process.
     *
     * @param processCwd workspace-relative cwd for the process start ("" = workspace root).
     * @param sessionCwd rootfs-absolute cwd reported to `session/new` (e.g. `/workspace`).
     */
    suspend fun connect(
        cliCommand: List<String>,
        workspaceRoot: String,
        processCwd: String = "",
        sessionCwd: String = "",
        extraEnv: Map<String, String> = emptyMap(),
    ): Result<Unit> = runCatching {
        val sameInvocation =
            this.cliCommand == cliCommand &&
                this.workspaceRoot == workspaceRoot &&
                this.processCwd == processCwd &&
                this.sessionCwd == sessionCwd &&
                this.agentEnvironment == extraEnv
        if (session?.isAlive == true && initialized && sameInvocation) return Result.success(Unit)
        closeInternal()
        this.cliCommand = cliCommand
        this.workspaceRoot = workspaceRoot
        this.processCwd = processCwd
        this.sessionCwd = sessionCwd
        this.agentEnvironment = extraEnv
        startProcess()
        initialize()
        initialized = true
    }

    private fun startProcess() {
        val proc = processRunner.start(workspaceRoot, cliCommand, processCwd, extraEnv = agentEnvironment)
        session = proc
        readerJob = scope.launch(Dispatchers.IO) {
            proc.stdoutLines.collect { line ->
                val element = runCatching { json.parseToJsonElement(line) }.getOrNull()
                    ?: return@collect
                val msg = runCatching { json.decodeFromJsonElement(AcpMessage.serializer(), element) }.getOrNull()
                    ?: return@collect
                if (msg.id != null) {
                    pendingResponses.remove(msg.id)?.let { channel ->
                        channel.trySend(element)
                    }
                } else {
                    notificationFlow.emit(element)
                }
            }
        }
        // 消费 stderr，避免管道缓冲填满导致 agent 进程阻塞
        stderrJob = scope.launch(Dispatchers.IO) {
            proc.stderrLines().collect { line ->
                Log.d(TAG, "[agent stderr] $line")
            }
        }
    }

    private suspend fun initialize() {
        val result = request(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put(
                    "capabilities",
                    buildJsonObject {
                        put("auth", buildJsonObject { put("terminal", buildJsonObject { }) })
                    }
                )
                put(
                    "info",
                    json.encodeToJsonElement(
                        AcpImplementation.serializer(),
                        AcpImplementation("rikkahub", "RikkaHub", "1.0.0")
                    )
                )
            },
        )
        checkNotNull(result) { "ACP initialize timed out" }
        agentCapabilities = result
    }

    /** Opens a new agent session and returns its session id. */
    suspend fun newSession(
        cwd: String = sessionCwd,
        mcpServers: List<JsonElement> = emptyList(),
    ): Result<String> {
        return try {
            val result = request(
                method = "session/new",
                params = json.encodeToJsonElement(
                    AcpNewSessionParams.serializer(),
                    AcpNewSessionParams(cwd = cwd.ifBlank { sessionCwd }, mcpServers = mcpServers)
                ),
            )
            val sessionId = result?.jsonObject?.get("sessionId")?.jsonPrimitive?.content
                ?: error("ACP session/new returned no sessionId: $result")
            activeSessionId = sessionId
            Result.success(sessionId)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Sends [promptText] to [sessionId] and returns a flow of JSON-RPC **notifications**
     * received while the agent works on the prompt. The flow completes when the agent
     * reports `session_idle` (or the connection drops).
     */
    fun prompt(sessionId: String, promptText: String): Flow<JsonElement> = flow {
        val sessionIdRef = sessionId
        // 先注册通知收集器再发送请求，避免 agent 在收集器就绪前产生输出导致丢消息
        coroutineScope {
            val local = Channel<JsonElement>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.IO) {
                notificationFlow.collect { element ->
                    if (belongsTo(sessionIdRef, element)) {
                        local.trySend(element)
                        if (isDone(element)) {
                            local.close()
                        }
                    }
                }
            }
            // 进程意外退出时结束等待，避免 prompt 流永久挂起
            val processWatcher = launch(Dispatchers.IO) {
                while (session?.isAlive == true) delay(250)
                local.close()
            }
            yield()
            val params = json.encodeToJsonElement(
                AcpPromptParams.serializer(),
                AcpPromptParams(
                    sessionId = sessionIdRef,
                    prompt = listOf(AcpTextContent(text = promptText))
                )
            )
            request(method = "session/prompt", params = params)
            while (true) {
                val element = local.receiveCatching().getOrNull() ?: break
                emit(element)
                if (isDone(element)) break
            }
            collector.cancel()
            processWatcher.cancel()
        }
    }

    private fun belongsTo(sessionId: String, element: JsonElement): Boolean {
        val params = element.jsonObject["params"] as? JsonObject ?: return false
        return params["sessionId"]?.jsonPrimitive?.content == sessionId
    }

    /** Closes the current session and terminates the sub-process. */
    fun close() {
        closeInternal()
        initialized = false
    }

    private fun closeInternal() {
        readerJob?.cancel()
        readerJob = null
        stderrJob?.cancel()
        stderrJob = null
        pendingResponses.values.forEach { it.close() }
        pendingResponses.clear()
        session?.close()
        session = null
        activeSessionId = null
    }

    private suspend fun request(method: String, params: JsonElement?): JsonElement? {
        val id = ++idCounter
        val channel = Channel<JsonElement>(Channel.CONFLATED)
        pendingResponses[id] = channel
        val payload = json.encodeToString(
            AcpMessage.serializer(),
            AcpMessage(id = id, method = method, params = params)
        )
        session?.writeLine(payload)
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            channel.receive().also {
                // already removed by reader; double-remove is harmless
                pendingResponses.remove(id)
            }
        }
        if (result == null) {
            pendingResponses.remove(id)
            channel.close()
        }
        return result?.let { json.decodeFromJsonElement(AcpMessage.serializer(), it).result }
    }

    /** `session_idle` or `session/end` marks the completion of a prompt turn. */
    private fun isDone(element: JsonElement): Boolean {
        val method = element.jsonObject["method"]?.jsonPrimitive?.content ?: return false
        if (method != "session/update") return false
        val update = element.jsonObject["params"]?.jsonObject?.get("update") ?: return false
        val kind = (update as? JsonObject)?.get("sessionUpdate")?.jsonPrimitive?.content ?: return false
        return kind == "session_idle" || kind == "session/end"
    }

    companion object {
        private const val TAG = "AcpAgentClient"
        private const val PROTOCOL_VERSION = 1L
        private const val REQUEST_TIMEOUT_MS = 120_000L
    }
}
