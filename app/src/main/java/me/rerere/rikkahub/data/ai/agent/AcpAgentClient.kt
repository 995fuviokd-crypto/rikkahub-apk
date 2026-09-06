package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.agent.AcpImplementation
import me.rerere.ai.agent.AcpMessage
import me.rerere.ai.agent.AcpNewSessionParams
import me.rerere.ai.agent.AcpPermissionOutcome
import me.rerere.ai.agent.AcpPermissionRequest
import me.rerere.ai.agent.AcpPromptParams
import me.rerere.ai.agent.AcpSessionUpdate
import me.rerere.ai.agent.AcpTextContent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import me.rerere.workspace.WorkspaceManager
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
/**
 * Thrown by [AcpAgentClient.prompt] when the agent sub-process dies before the turn
 * completes. Callers can catch this to attempt a transparent recovery (process restart
 * plus `session/load`) instead of surfacing a truncated reply.
 */
class AgentProcessDiedException(message: String) : IllegalStateException(message)

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

    private val pendingResponses = ConcurrentHashMap<Long, Channel<AcpMessage>>()
    private val notificationFlow = MutableSharedFlow<AcpMessage>(extraBufferCapacity = 512)

    private val notifications: Flow<AcpMessage> = notificationFlow.asSharedFlow()
    private var initialized = false
    private var activeSessionId: String? = null
    private var idCounter = 0L

    /** A tool-permission prompt surfaced to the UI, keyed by the JSON-RPC request id. */
    data class PermissionPrompt(
        val requestId: Long,
        val sessionId: String,
        val toolCallId: String?,
        val title: String?,
        val kind: String?,
        val options: List<me.rerere.ai.agent.AcpPermissionOption>,
    )

    private val pendingPermissionDeferreds = ConcurrentHashMap<Long, CompletableDeferred<AcpPermissionOutcome>>()
    private val _permissionPrompts = MutableStateFlow<Map<Long, PermissionPrompt>>(emptyMap())

    /** Live tool-permission prompts awaiting a user decision. */
    val permissionPrompts: StateFlow<Map<Long, PermissionPrompt>> = _permissionPrompts.asStateFlow()

    /**
     * Resolves a pending permission prompt. Passing `null` selects the protocol's
     * "cancelled" outcome (the agent treats it as if the user dismissed the tool call).
     */
    fun respondPermission(requestId: Long, optionId: String?) {
        val deferred = synchronized(pendingPermissionDeferreds) { pendingPermissionDeferreds.remove(requestId) }
        _permissionPrompts.update { it - requestId }
        when {
            deferred == null -> return
            optionId != null -> deferred.complete(AcpPermissionOutcome.selected(optionId))
            else -> deferred.complete(AcpPermissionOutcome.CANCELLED)
        }
    }

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
            val readerScope = this
            proc.stdoutLines.collect { line ->
                // 单次解析：直接从行文本反序列化 AcpMessage（params/result 保留为内嵌
                // JsonElement 子树），下游全部复用该结构，避免同一消息反复解析。
                val msg = runCatching { json.decodeFromString(AcpMessage.serializer(), line) }
                    .getOrNull()
                    ?: return@collect
                val msgId = msg.id
                val msgMethod = msg.method
                when {
                    // Response to one of our own requests.
                    msgId != null && msgMethod == null -> {
                        pendingResponses.remove(msgId)?.let { channel ->
                            channel.trySend(msg)
                        }
                    }
                    // Request initiated by the agent (permission prompt, fs access, …).
                    // Handled on a separate coroutine so a long-running decision never
                    // blocks the reader from consuming subsequent stdout messages.
                    msgId != null && msgMethod != null -> {
                        readerScope.launch(Dispatchers.IO) {
                            runCatching { handleAgentRequest(msgId, msg) }
                                .onFailure { Log.w(TAG, "agent request $msgMethod failed", it) }
                        }
                    }
                    else -> notificationFlow.emit(msg)
                }
            }
        }
        // 消费 stderr，避免管道缓冲填满导致 agent 进程阻塞；同时保留尾部片段用于
        // 连接失败时给出可读的根因（npm 包缺失 / Node 版本不足 / 认证过期等）。
        stderrJob = scope.launch(Dispatchers.IO) {
            proc.stderrLines.collect { line ->
                Log.d(TAG, "[agent stderr] $line")
                synchronized(recentStderr) {
                    recentStderr.addLast(line)
                    while (recentStderr.size > RECENT_STDERR_LINES) recentStderr.removeFirst()
                }
            }
        }
    }

    private val recentStderr = ArrayDeque<String>()

    private fun recentStderrSnapshot(): String = synchronized(recentStderr) {
        recentStderr.joinToString("\n").trim()
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
                        // Advertise workspace file access so agents may read/write files
                        // through the client (routed to the host-side workspace dir with
                        // path-traversal protection in [resolveWorkspaceFile]).
                        put(
                            "fs",
                            buildJsonObject {
                                put("readTextFile", true)
                                put("writeTextFile", true)
                            }
                        )
                        // Advertise terminal hosting so agents may run commands through
                        // the client's PRoot sandbox (terminal/create + output/kill/…).
                        put("terminal", true)
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
        checkNotNull(result) {
            buildString {
                append("ACP initialize timed out or failed")
                val stderr = recentStderrSnapshot()
                if (stderr.isNotBlank()) {
                    append("\n--- agent stderr (tail) ---\n")
                    append(stderr)
                }
            }
        }
        agentCapabilities = result
        loadSessionSupported = runCatching {
            result.jsonObject["agentCapabilities"]?.jsonObject
                ?.get("loadSession")?.jsonPrimitive?.content == "true"
        }.getOrDefault(false)
    }

    /**
     * Whether the agent advertised the `loadSession` capability in its initialize
     * response. When true, [loadSession] can restore a previous session after a
     * process restart so agent-side context survives app restarts.
     */
    @Volatile
    var loadSessionSupported: Boolean = false
        private set

    /**
     * Restores a previously-created agent session (`session/load`). Returns the loaded
     * session id on success; callers fall back to [newSession] when this fails (e.g.
     * the agent lost its persisted state).
     */
    suspend fun loadSession(
        sessionId: String,
        cwd: String = sessionCwd,
        mcpServers: List<JsonElement> = emptyList(),
    ): Result<String> {
        if (!loadSessionSupported) {
            return Result.failure(IllegalStateException("agent does not support session/load"))
        }
        return try {
            val result = request(
                method = "session/load",
                params = json.encodeToJsonElement(
                    AcpNewSessionParams.serializer(),
                    AcpNewSessionParams(cwd = cwd.ifBlank { sessionCwd }, mcpServers = mcpServers)
                ).let { params ->
                    // session/load reuses session/new's shape with an extra sessionId field
                    buildJsonObject {
                        put("sessionId", sessionId)
                        (params as? JsonObject)?.forEach { (k, v) -> if (k != "sessionId") put(k, v) }
                    }
                },
            )
            val loaded = result?.jsonObject?.get("sessionId")?.jsonPrimitive?.content ?: sessionId
            activeSessionId = loaded
            Result.success(loaded)
        } catch (e: Throwable) {
            Result.failure(e)
        }
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
            parseSessionModes(sessionId, result)
            activeSessionId = sessionId
            Result.success(sessionId)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Session modes advertised by the agent for [sessionId] (id + display name pairs). */
    data class SessionModes(
        val availableModes: List<Pair<String, String>> = emptyList(),
        val currentModeId: String? = null,
    )

    private val sessionModeInfos = ConcurrentHashMap<String, SessionModes>()

    /** Returns parsed session-mode info for [sessionId], or null when unavailable. */
    fun sessionModes(sessionId: String): Pair<List<Pair<String, String>>, String?> =
        sessionModeInfos[sessionId]?.let { it.availableModes to it.currentModeId }
            ?: (emptyList<Pair<String, String>>() to null)

    private fun parseSessionModes(sessionId: String, result: JsonElement) {
        val modesObj = (result as? JsonObject)?.get("modes") as? JsonObject ?: return
        val current = (modesObj["currentModeId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val available = (modesObj["availableModes"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { mode ->
                val obj = mode as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: return@mapNotNull null
                val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: id
                id to name
            }
            .orEmpty()
        if (available.isNotEmpty() || current != null) {
            sessionModeInfos[sessionId] = SessionModes(available, current)
        }
    }

    /**
     * Sends [promptText] to [sessionId] and returns a flow of parsed session updates.
     * The flow completes when the agent reports `session_idle` / `session/end`
     * (or the connection drops). Cancelling collection (user taps "stop") sends
     * `session/cancel` so the agent aborts the in-flight turn, mirroring desktop
     * client behavior. Updates are decoded exactly once here — no re-parsing downstream.
     *
     * @throws AgentProcessDiedException when the sub-process exits before the turn
     *   completes, so callers can distinguish a crash from a normal end and recover.
     */
    fun prompt(sessionId: String, promptText: String): Flow<AcpSessionUpdate> = flow {
        val sessionIdRef = sessionId
        var completed = false
        var doneReceived = false
        try {
            // 先注册通知收集器再发送请求，避免 agent 在收集器就绪前产生输出导致丢消息
            coroutineScope {
                val local = Channel<AcpSessionUpdate>(Channel.UNLIMITED)
                val collector = launch(Dispatchers.IO) {
                    notificationFlow.collect { msg ->
                        if (belongsToSession(sessionIdRef, msg)) {
                            val update = msg.toUpdateOrNull() ?: return@collect
                            local.trySend(update)
                            if (isTerminalUpdate(update)) {
                                doneReceived = true
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
                val promptResult = request(method = "session/prompt", params = params)
                if (promptResult == null && session?.isAlive == false) {
                    throw AgentProcessDiedException("agent exited before session/prompt was accepted")
                }
                while (true) {
                    val update = local.receiveCatching().getOrNull() ?: break
                    emit(update)
                    if (isTerminalUpdate(update)) break
                }
                collector.cancel()
                processWatcher.cancel()
            }
            // 流结束却没收到完成信号且进程已死：显式报告崩溃（区别于正常轮次完成）。
            if (!doneReceived && session?.isAlive == false) {
                throw AgentProcessDiedException("agent process exited mid-turn")
            }
            completed = true
        } finally {
            if (!completed && session?.isAlive == true) {
                runCatching { cancelSession(sessionIdRef) }
            }
        }
    }

    /**
     * Restarts the agent sub-process after a crash: tears down the dead process,
     * starts a fresh one and re-runs the initialize handshake. Callers then restore
     * conversation state via [loadSession] (when supported) or fall back to
     * [newSession].
     */
    suspend fun restart(): Result<Unit> = runCatching {
        check(cliCommand.isNotEmpty()) { "client never connected; nothing to restart" }
        closeInternal()
        startProcess()
        initialize()
        initialized = true
    }

    /**
     * Asks the agent to abort the in-flight turn (`session/cancel` notification).
     * Returns false when there is no live process to deliver it to.
     */
    suspend fun cancelSession(sessionId: String): Boolean {
        val proc = session?.takeIf { it.isAlive } ?: return false
        val payload = json.encodeToString(
            AcpMessage.serializer(),
            AcpMessage(
                method = "session/cancel",
                params = buildJsonObject { put("sessionId", sessionId) }
            )
        )
        return runCatching { proc.writeLine(payload) }.isSuccess
    }

    /**
     * Switches the session mode (`session/set_mode`, e.g. plan / acceptEdits / code).
     * Returns the raw result (null on timeout/error); agents acknowledge with the
     * updated mode info, and later confirm through `current_mode_update` notifications.
     */
    suspend fun setSessionMode(sessionId: String, modeId: String): JsonElement? = try {
        request(
            method = "session/set_mode",
            params = buildJsonObject {
                put("sessionId", sessionId)
                put("modeId", modeId)
            },
        )
    } catch (e: Throwable) {
        Log.w(TAG, "setSessionMode($modeId) failed", e)
        null
    }

    private fun belongsToSession(sessionId: String, msg: AcpMessage): Boolean =
        (msg.params as? JsonObject)?.get("sessionId")?.jsonPrimitive?.content == sessionId

    /** Decodes the `update` payload of a session/update notification (once per message). */
    private fun AcpMessage.toUpdateOrNull(): AcpSessionUpdate? {
        if (method != "session/update") return null
        val updateJson = (params as? JsonObject)?.get("update") ?: return null
        return runCatching {
            json.decodeFromJsonElement(AcpSessionUpdate.serializer(), updateJson)
        }.getOrNull()
    }

    /** `session_idle` or `session/end` marks the completion of a prompt turn. */
    private fun isTerminalUpdate(update: AcpSessionUpdate): Boolean =
        update.sessionUpdate == "session_idle" || update.sessionUpdate == "session/end"

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
        // Fail any in-flight permission waits; their coroutines respond with an error
        // and the UI prompts are dropped since the agent process is going away.
        synchronized(pendingPermissionDeferreds) {
            pendingPermissionDeferreds.values.forEach { it.cancel() }
            pendingPermissionDeferreds.clear()
        }
        _permissionPrompts.value = emptyMap()
        // Tear down any terminals the agent left running.
        val staleTerminals = terminals.values.toList().also { terminals.clear() }
        staleTerminals.forEach { terminal ->
            if (terminal.closed.compareAndSet(false, true)) {
                runCatching { terminal.proc.close() }
            }
        }
        session?.close()
        session = null
        activeSessionId = null
    }

    private suspend fun request(method: String, params: JsonElement?): JsonElement? {
        val id = ++idCounter
        val channel = Channel<AcpMessage>(Channel.CONFLATED)
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
        return result?.result
    }

    /**
     * Handles a request initiated by the agent and writes back the JSON-RPC response.
     * Supported methods: `session/request_permission`, `fs/read_text_file`,
     * `fs/write_text_file` and the terminal lifecycle methods. Unknown methods get a
     * `-32601` error so the agent never hangs waiting for a reply.
     */
    private suspend fun handleAgentRequest(id: Long, msg: AcpMessage) {
        val method = msg.method ?: return
        val params = msg.params as? JsonObject
        when (method) {
            "session/request_permission" -> {
                val request = params?.let {
                    runCatching { json.decodeFromJsonElement<AcpPermissionRequest>(it) }.getOrNull()
                }
                if (request == null) {
                    respondError(id, -32602, "invalid session/request_permission params")
                    return
                }
                val deferred = CompletableDeferred<AcpPermissionOutcome>()
                synchronized(pendingPermissionDeferreds) { pendingPermissionDeferreds[id] = deferred }
                _permissionPrompts.update {
                    it + (
                        id to PermissionPrompt(
                            requestId = id,
                            sessionId = request.sessionId,
                            toolCallId = request.toolCall?.toolCallId,
                            title = request.toolCall?.title,
                            kind = request.toolCall?.kind,
                            options = request.options,
                        )
                        )
                }
                try {
                    val outcome = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() }
                        ?: AcpPermissionOutcome.CANCELLED
                    respond(id, json.encodeToJsonElement(AcpPermissionOutcome.serializer(), outcome))
                } finally {
                    synchronized(pendingPermissionDeferreds) { pendingPermissionDeferreds.remove(id) }
                    _permissionPrompts.update { it - id }
                }
            }

            "fs/read_text_file" -> {
                runCatching { handleReadTextFile(params) }
                    .onSuccess { content ->
                        respond(
                            id,
                            buildJsonObject {
                                put("content", content)
                            }
                        )
                    }
                    .onFailure { respondError(id, -32000, it.message ?: "read failed") }
            }

            "fs/write_text_file" -> {
                runCatching { handleWriteTextFile(params) }
                    .onSuccess { respond(id, buildJsonObject { }) }
                    .onFailure { respondError(id, -32000, it.message ?: "write failed") }
            }

            "terminal/create" -> {
                runCatching { handleTerminalCreate(params) }
                    .onSuccess { terminalId ->
                        respond(id, buildJsonObject { put("terminalId", terminalId) })
                    }
                    .onFailure { respondError(id, -32000, it.message ?: "terminal create failed") }
            }

            "terminal/output" -> {
                runCatching { handleTerminalOutput(params) }
                    .onSuccess { respond(id, it) }
                    .onFailure { respondError(id, -32000, it.message ?: "terminal output failed") }
            }

            "terminal/wait_for_exit" -> {
                runCatching { handleTerminalWaitForExit(params) }
                    .onSuccess { respond(id, it) }
                    .onFailure { respondError(id, -32000, it.message ?: "wait_for_exit failed") }
            }

            "terminal/kill" -> {
                runCatching { killTerminal(params) }
                    .onSuccess { killed ->
                        if (killed) respond(id, buildJsonObject { }) else respondError(id, -32002, "unknown terminalId")
                    }
                    .onFailure { respondError(id, -32000, it.message ?: "kill failed") }
            }

            "terminal/release" -> {
                runCatching { releaseTerminal(params) }
                    .onSuccess { released ->
                        if (released) respond(id, buildJsonObject { }) else respondError(id, -32002, "unknown terminalId")
                    }
                    .onFailure { respondError(id, -32000, it.message ?: "release failed") }
            }

            else -> respondError(id, -32601, "method not supported by client: $method")
        }
    }

    /** A hosted terminal process with its captured output ring buffer. */
    private class TerminalSession(
        val proc: WorkspaceProcessSession,
        val outputByteLimit: Long,
    ) {
        val buffer = StringBuilder()
        var truncated = false
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private val terminals = ConcurrentHashMap<String, TerminalSession>()
    private var terminalCounter = 0L

    /** Starts a sandboxed process for the agent and registers it under a fresh id. */
    private suspend fun handleTerminalCreate(params: JsonObject?): String {
        params ?: throw IllegalArgumentException("missing params")
        val command = params["command"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("missing command")
        return createTerminal(
            command = command,
            args = jsonArgList(params["args"]),
            cwd = params["cwd"]?.jsonPrimitive?.content,
            env = jsonEnvMap(params["env"]),
            outputByteLimit = (params["outputByteLimit"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toLongOrNull() ?: DEFAULT_TERMINAL_OUTPUT_LIMIT,
        )
    }

    private fun createTerminal(
        command: String,
        args: List<String>,
        cwd: String?,
        env: Map<String, String>,
        outputByteLimit: Long,
    ): String {
        require(command.isNotBlank()) { "empty command" }
        // Map container-side /workspace/... cwd to a workspace-relative path.
        val relCwd = when {
            cwd.isNullOrBlank() -> ""
            cwd == WorkspaceManager.ROOTFS_WORKSPACE_DIR -> ""
            cwd.startsWith("${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/") ->
                cwd.removePrefix("${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/").trim('/')
            cwd.startsWith("/") -> throw SecurityException("cwd outside /workspace denied: $cwd")
            else -> cwd.trim('/')
        }
        val proc = processRunner.start(
            root = workspaceRoot,
            command = listOf(command) + args,
            cwd = relCwd,
            extraEnv = env,
        )
        val session = TerminalSession(proc, outputByteLimit)
        return synchronized(terminals) {
            terminalCounter += 1
            val terminalId = "term-$terminalCounter"
            terminals[terminalId] = session
            terminalId
        }.also { terminalId ->
            // Capture stdout+stderr into the ring buffer; the underlying Flow delivers
            // line-wise so a newline is appended to preserve original formatting.
            scope.launch(Dispatchers.IO) {
                runCatching {
                    proc.stdoutLines.collect { appendTerminalOutput(terminalId, it + "\n", session) }
                }
            }
            scope.launch(Dispatchers.IO) {
                runCatching {
                    proc.stderrLines.collect { appendTerminalOutput(terminalId, it + "\n", session) }
                }
            }
        }
    }

    private fun appendTerminalOutput(terminalId: String, text: String, session: TerminalSession) {
        if (session.closed.get()) return
        synchronized(session.buffer) {
            session.buffer.append(text)
            if (session.outputByteLimit > 0 && session.buffer.length > session.outputByteLimit) {
                // Drop from the head, keeping the most recent output for the agent.
                val cut = (session.buffer.length - session.outputByteLimit).toInt().coerceAtLeast(1)
                session.buffer.delete(0, cut)
                session.truncated = true
            }
        }
        // Hot path: skip logd writes unless verbose logging is explicitly enabled.
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "[terminal:$terminalId] ${text.trimEnd()}")
        }
    }

    private fun terminalSession(params: JsonObject?): TerminalSession? {
        val terminalId = params?.get("terminalId")?.jsonPrimitive?.content ?: return null
        return terminals[terminalId]
    }

    private fun handleTerminalOutput(params: JsonObject?): JsonObject {
        val session = terminalSession(params) ?: throw IllegalArgumentException("unknown terminalId")
        val (output, truncated) = synchronized(session.buffer) { session.buffer.toString() to session.truncated }
        return buildJsonObject {
            put("output", output)
            put("truncated", truncated)
            if (!session.proc.isAlive) {
                put("exitStatus", buildJsonObject { put("exitCode", 0); put("signal", null) })
            }
        }
    }

    private suspend fun handleTerminalWaitForExit(params: JsonObject?): JsonObject {
        val session = terminalSession(params) ?: throw IllegalArgumentException("unknown terminalId")
        val exitCode = if (session.proc.isAlive) session.proc.waitForExit() ?: 0 else 0
        return buildJsonObject {
            put("exitCode", exitCode)
            put("signal", null)
        }
    }

    private fun killTerminal(params: JsonObject?): Boolean {
        val session = terminalSession(params) ?: return false
        if (session.closed.compareAndSet(false, true)) {
            session.proc.close()
        }
        return true
    }

    private fun releaseTerminal(params: JsonObject?): Boolean {
        val terminalId = params?.get("terminalId")?.jsonPrimitive?.content ?: return false
        val session = synchronized(terminals) { terminals.remove(terminalId) } ?: return false
        if (session.closed.compareAndSet(false, true)) {
            session.proc.close()
        }
        return true
    }

    /** Parses ACP env variable pairs ({name, value}) into a plain map. */
    private fun jsonEnvMap(element: JsonElement?): Map<String, String> {
        val array = element as? kotlinx.serialization.json.JsonArray ?: return emptyMap()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            name to (obj["value"]?.jsonPrimitive?.content ?: "")
        }.toMap()
    }

    /** Parses a JSON array of strings into a Kotlin list. */
    private fun jsonArgList(element: JsonElement?): List<String> {
        val array = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    /** Maps a container-side `/workspace/...` path onto the host workspace files dir. */
    private fun resolveWorkspaceFile(path: String): File {
        require(path.isNotBlank()) { "empty path" }
        val canonicalRoot = File(workspaceRoot, FILES_DIR_NAME).canonicalFile
        val relative = when {
            path == "/workspace" -> ""
            path.startsWith("/workspace/") -> path.removePrefix("/workspace/")
            path.startsWith("/") -> throw SecurityException("access outside /workspace denied: $path")
            else -> path
        }
        val target = File(canonicalRoot, relative).canonicalFile
        if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) {
            throw SecurityException("path traversal denied: $path")
        }
        return target
    }

    @Suppress("SameParameterValue")
    private fun handleReadTextFile(params: JsonObject?): String {
        params ?: throw IllegalArgumentException("missing params")
        val path = params["path"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("missing path")
        val file = resolveWorkspaceFile(path)
        if (!file.isFile) throw IllegalArgumentException("file not found: $path")
        if (file.length() > MAX_FS_TRANSFER_BYTES) {
            throw IllegalArgumentException("file too large (${file.length()} bytes): $path")
        }
        val text = file.readText(Charsets.UTF_8)
        val line = (params["line"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        val limit = (params["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        if (line == null && limit == null) return text
        val lines = text.lines()
        val start = ((line ?: 1) - 1).coerceIn(0, lines.size)
        val end = if (limit != null) (start + limit).coerceAtMost(lines.size) else lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun handleWriteTextFile(params: JsonObject?) {
        params ?: throw IllegalArgumentException("missing params")
        val path = params["path"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("missing path")
        val content =
            (params["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: throw IllegalArgumentException("missing content")
        val file = resolveWorkspaceFile(path)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private suspend fun respond(id: Long, result: JsonElement?) {
        val payload = json.encodeToString(
            AcpMessage.serializer(),
            AcpMessage(id = id, result = result ?: JsonObject(emptyMap()))
        )
        session?.writeLine(payload)
    }

    private suspend fun respondError(id: Long, code: Int, message: String) {
        val payload = json.encodeToString(
            AcpMessage.serializer(),
            AcpMessage(
                id = id,
                error = buildJsonObject {
                    put("code", code)
                    put("message", message)
                }
            )
        )
        session?.writeLine(payload)
    }

    companion object {
        private const val TAG = "AcpAgentClient"
        private const val PROTOCOL_VERSION = 1L
        private const val REQUEST_TIMEOUT_MS = 120_000L

        /** How long a permission prompt stays open before auto-cancelling. */
        private const val PERMISSION_TIMEOUT_MS = 600_000L

        /** Upper bound for fs/read_text_file transfers to avoid OOM on huge files. */
        private const val MAX_FS_TRANSFER_BYTES = 4L * 1024 * 1024

        /** Default ring-buffer size for hosted terminal output (1 MiB). */
        private const val DEFAULT_TERMINAL_OUTPUT_LIMIT = 1L * 1024 * 1024

        /** Name of the workspace sub-dir bound to `/workspace` inside the container. */
        private const val FILES_DIR_NAME = "files"

        /** Stderr lines kept for diagnostics when a connection fails. */
        private const val RECENT_STDERR_LINES = 20
    }
}
