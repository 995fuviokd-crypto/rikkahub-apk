package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.rerere.ai.agent.SessionUpdateBridge
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AgentPlatform
import me.rerere.ai.provider.AgentSubagentConfig
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceProcessRunner
import kotlin.uuid.Uuid

/** UI-facing alias for an ACP tool-permission prompt awaiting a decision. */
typealias AgentPrompt = AcpAgentClient.PermissionPrompt

/**
 * Manages the ACP agent sub-process lifecycle for generation paths.
 * * Responsibilities:
 * - **Process reuse**: one [AcpAgentClient] (one sub-process) per
 *   `(workspace root, platform, cli args, environment)` tuple. CLI startup is expensive
 *   (Node + agent bundle), so the same process is kept across turns.
 * - **Session reuse per conversation**: each `conversationId` maps to a stable ACP session,
 *   so agent-side history/context survives across prompts in the same conversation.
 * - **Message rendering**: [UIMessage] lists are rendered into a single ACP prompt text;
 *   the full context is sent on the first prompt of a session, later prompts only send the
 *   incremental user message (the agent already remembers earlier turns).
 *
 * Output is translated into [StreamChunk] events via [SessionUpdateBridge] so the existing
 * [me.rerere.ai.ui.StreamChunkHandler] pipeline can consume it unchanged.
 */
class AcpRuntime(
    private val environmentManager: AcpEnvironmentManager,
    private val processRunner: WorkspaceProcessRunner,
    private val json: Json,
    private val scope: AppScope,
    private val mcpServersBuilder: AcpMcpServersBuilder,
    private val sessionStore: AcpSessionStore = AcpSessionStore(),
) {
    private data class SessionBinding(
        val client: AcpAgentClient,
        val sessionId: String,
        val sentUserMessageIds: MutableSet<Uuid> = mutableSetOf(),
        /** Session modes advertised by the agent in the session/new response. */
        val availableModes: List<SessionModeInfo> = emptyList(),
        val currentModeId: String? = null,
        /** True when the session was restored via `session/load` after a restart. */
        val restored: Boolean = false,
    )

    /** A selectable session mode reported by the agent (id + display name). */
    data class SessionModeInfo(val id: String, val name: String)

    private val clients = mutableMapOf<String, AcpAgentClient>()
    private val sessionBindings = mutableMapOf<String, SessionBinding>()
    private val mutex = Mutex()

    private val clientList = MutableStateFlow<List<AcpAgentClient>>(emptyList())

    /**
     * Aggregated tool-permission prompts across every live agent process, keyed by
     * JSON-RPC request id. The chat UI renders these as approval dialogs; desktop-style
     * "allow once / always / reject" decisions are forwarded via [respondPermission].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val permissionPrompts: StateFlow<Map<Long, AcpAgentClient.PermissionPrompt>> =
        clientList.flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyMap())
            else combine(list.map { it.permissionPrompts }) { maps ->
                maps.fold(mutableMapOf<Long, AcpAgentClient.PermissionPrompt>()) { acc, m ->
                    acc.apply { putAll(m) }
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Forwards a user decision (or dismissal when [optionId] is null) to the owning agent. */
    fun respondPermission(requestId: Long, optionId: String?) {
        val owner = clients.values.firstOrNull { it.permissionPrompts.value.containsKey(requestId) }
        owner?.respondPermission(requestId, optionId)
            ?: Log.w(TAG, "respondPermission: no live client owns request $requestId")
    }

    /**
     * Streams a single assistant turn through the bound platform agent.
     *
     * If the agent sub-process crashes mid-turn, one transparent recovery is attempted:
     * restart the process, restore the session via `session/load` (falling back to a
     * fresh session), and replay the full context. A second consecutive crash propagates
     * the error to the normal failure path.
     *
     * @param messages the full conversation history ending with the latest user message.
     */
    fun streamText(
        model: Model,
        messages: List<UIMessage>,
        workspaceRoot: String,
        workspaceCwd: String?,
        conversationId: Uuid?,
    ): Flow<StreamChunk> = flow {
        val platform = model.platformAgent ?: error("Model has no platform agent bound")
        val root = workspaceRoot.ifBlank { error("Platform agent requires a bound workspace") }

        environmentManager.ensureReady(root, platform).getOrThrow()

        var recoveryAttempts = 0
        while (true) {
            val client = getOrCreateClient(model, platform, root, workspaceCwd)
            val binding = getOrCreateSession(client, platform, root, conversationId)
            val promptText = renderPrompt(messages, binding)

            Log.i(TAG, "prompting ${platform.name} session=${binding.sessionId}")
            val bridge = SessionUpdateBridge()
            try {
                // client 已将通知解码为 AcpSessionUpdate（每条消息只解析一次）
                client.prompt(binding.sessionId, promptText).collect { update ->
                    bridge.translate(update).forEach { emit(it) }
                }
                break
            } catch (e: AgentProcessDiedException) {
                // 中断/异常时也闭合文本、思考与工具卡片状态, 避免 UI 残留进行中的部件
                bridge.finish().forEach { emit(it) }
                recoveryAttempts += 1
                if (recoveryAttempts > MAX_RECOVERY_ATTEMPTS) throw e
                Log.w(TAG, "agent died mid-turn; attempting recovery #${recoveryAttempts - 1}", e)
                recoverClient(client, binding)
                // 恢复后全量重发上下文：无法确定 agent 收到了多少输入，
                // 重复上下文的代价远小于丢失上下文。
                binding.sentUserMessageIds.clear()
            } catch (e: Throwable) {
                bridge.finish().forEach { emit(it) }
                throw e
            }
        }
    }

    /** Restarts a dead agent process and restores its session state. */
    private suspend fun recoverClient(client: AcpAgentClient, binding: SessionBinding) {
        val previousSessionId = binding.sessionId
        client.restart().getOrThrow()
        val mcpServers = mcpServersBuilder.build()
        val restoredId = if (client.loadSessionSupported) {
            client.loadSession(previousSessionId, mcpServers = mcpServers).getOrNull()
        } else {
            null
        } ?: client.newSession(mcpServers = mcpServers).getOrThrow()
        if (restoredId != previousSessionId) {
            // 更新绑定内的 sessionId，后续轮次继续使用恢复后的会话
            val key = sessionBindings.entries.firstOrNull { it.value === binding }?.key
            if (key != null) {
                sessionBindings[key] = binding.copy(sessionId = restoredId)
            }
        }
        Log.i(TAG, "agent recovered: session $previousSessionId -> $restoredId")
    }

    /** Closes every agent process and forgets all sessions (e.g. app teardown). */
    fun closeAll() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        sessionBindings.clear()
        clientList.value = emptyList()
    }

    /**
     * 子代理配置 → agent 进程环境变量。
     *
     * 仅在启用且选择 DSH 引擎（或 AUTO 下 DSH 平台）时下发，
     * 供 DSH 侧 subagent 插件消费；其他平台安全忽略未知变量。
     */
    private fun buildSubagentEnv(model: Model, platform: AgentPlatform): Map<String, String> {
        val config = model.agentSubagent ?: return emptyMap()
        if (!config.enabled) return emptyMap()
        val useDsh = config.engine == AgentSubagentConfig.ENGINE_DSH ||
            (config.engine == AgentSubagentConfig.ENGINE_AUTO && platform == AgentPlatform.DEEPSEEK_HARNESS)
        if (!useDsh) return emptyMap()
        return buildMap {
            put("DSH_SUBAGENT_ENABLED", "true")
            put("DSH_SUBAGENT_MAX_DEPTH", config.maxDepth.toString())
            config.modelId?.let { put("DSH_SUBAGENT_MODEL", it.toString()) }
        }
    }
    private suspend fun getOrCreateClient(
        model: Model,
        platform: AgentPlatform,
        root: String,
        workspaceCwd: String?,
    ): AcpAgentClient = mutex.withLock {
        val cli = environmentManager.cliCommand(platform, model.agentArguments)
        val env = model.agentEnvironment + buildSubagentEnv(model, platform)
        val processCwd = relativeCwd(workspaceCwd)
        val key = listOf(
            root,
            platform.name,
            processCwd,
            cli.joinToString(" "),
            env.entries.sortedBy { it.key }.joinToString(","),
        ).joinToString("|")
        clients[key] ?: run {
            val client = AcpAgentClient(
                processRunner = processRunner,
                json = json,
                scope = scope,
            )
            val sessionCwd = workspaceCwd?.ifBlank { null } ?: WorkspaceManager.ROOTFS_WORKSPACE_DIR
            client.connect(
                cliCommand = cli,
                workspaceRoot = root,
                processCwd = processCwd,
                sessionCwd = sessionCwd,
                extraEnv = env,
            ).getOrThrow()
            clients[key] = client
            clientList.value = clients.values.toList()
            client
        }
    }

    private suspend fun getOrCreateSession(
        client: AcpAgentClient,
        platform: AgentPlatform,
        root: String,
        conversationId: Uuid?,
    ): SessionBinding = mutex.withLock {
        val mcpServers = mcpServersBuilder.build()
        if (conversationId == null) {
            // 无对话（如翻译）：每次新建独立 session，避免上次上下文污染本次结果
            val sessionId = client.newSession(mcpServers = mcpServers).getOrThrow()
            return@withLock SessionBinding(client, sessionId, availableModes = emptyList())
        }
        val key = "${root}|${platform.name}|$conversationId"
        sessionBindings[key]?.takeIf { it.client === client } ?: run {
            val storeKey = "${platform.name}|$conversationId"
            val previous = sessionStore.load(root, storeKey)
            var restored = false
            val sessionId = previous?.let { prev ->
                client.loadSession(prev, mcpServers = mcpServers).getOrNull()?.also {
                    restored = true
                }
            } ?: client.newSession(mcpServers = mcpServers).getOrThrow()
            if (sessionId != previous) {
                sessionStore.save(root, storeKey, sessionId)
            }
            val (modesRaw, currentMode) = client.sessionModes(sessionId)
            SessionBinding(
                client = client,
                sessionId = sessionId,
                availableModes = modesRaw.map { (id, name) -> SessionModeInfo(id, name) },
                currentModeId = currentMode,
                restored = restored,
            ).also {
                sessionBindings[key] = it
                publishSessionModes(conversationId, it)
            }
        }
    }

    /** Live session-mode info per conversation key, for the UI mode switcher. */
    fun sessionModes(root: String, platform: AgentPlatform, conversationId: Uuid): Pair<List<SessionModeInfo>, String?> =
        sessionBindings["${root}|${platform.name}|$conversationId"]
            ?.let { it.availableModes to it.currentModeId }
            ?: (emptyList<SessionModeInfo>() to null)

    /** UI-facing snapshot of the session modes bound to a conversation. */
    data class AgentSessionModes(
        val modes: List<SessionModeInfo> = emptyList(),
        val currentModeId: String? = null,
    )

    private val _sessionModeStates = MutableStateFlow<Map<Uuid, AgentSessionModes>>(emptyMap())

    /**
     * Reactive session-mode state keyed by conversation. Empty [AgentSessionModes.modes]
     * means the agent did not advertise any modes and the UI hides the switcher.
     */
    val sessionModeStates: StateFlow<Map<Uuid, AgentSessionModes>> = _sessionModeStates.asStateFlow()

    private fun publishSessionModes(conversationId: Uuid?, binding: SessionBinding) {
        if (conversationId == null) return
        if (binding.availableModes.isEmpty()) return
        _sessionModeStates.update {
            it + (
                conversationId to AgentSessionModes(
                    modes = binding.availableModes,
                    currentModeId = binding.currentModeId,
                )
                )
        }
    }

    /** Finds the binding for a conversation regardless of its workspace/platform key parts. */
    private fun findBinding(conversationId: Uuid): SessionBinding? =
        sessionBindings.entries.firstOrNull { it.key.endsWith("|$conversationId") }?.value

    /**
     * Switches the session mode of the bound conversation (`session/set_mode`).
     * Returns true when the agent acknowledged the switch.
     */
    suspend fun setSessionModeFor(conversationId: Uuid, modeId: String): Boolean {
        val binding = mutex.withLock { findBinding(conversationId) } ?: return false
        val result = binding.client.setSessionMode(binding.sessionId, modeId)
        if (result != null) {
            val updated = binding.copy(currentModeId = modeId)
            val key = sessionBindings.entries.firstOrNull { it.value === binding }?.key
            if (key != null) sessionBindings[key] = updated
            publishSessionModes(conversationId, updated)
        }
        return result != null
    }

    private fun renderPrompt(messages: List<UIMessage>, binding: SessionBinding): String {
        if (binding.restored && binding.sentUserMessageIds.isEmpty()) {
            // 恢复的会话（session/load）：agent 侧已有完整历史，只补发最新一条用户消息，
            // 避免全量重放导致上下文重复。标记全部消息为已发送，后续走增量路径。
            val latestUser = messages.lastOrNull { it.role == MessageRole.USER }
            if (latestUser != null) {
                binding.sentUserMessageIds.addAll(messages.mapNotNull { it.id })
                return "<user>\n${latestUser.toText()}\n</user>"
            }
        }
        val first = binding.sentUserMessageIds.isEmpty()
        if (first) {
            binding.sentUserMessageIds.addAll(messages.mapNotNull { it.id })
            return renderFullContext(messages)
        }
        // 增量：只发送尚未发给 agent 的最新用户消息（agent 已通过会话记忆保留历史）
        val pending = messages.filter { it.role == MessageRole.USER && it.id !in binding.sentUserMessageIds }
        if (pending.isEmpty()) {
            // 没有新用户消息（如重试/工具继续），回退为完整上下文
            return renderFullContext(messages)
        }
        binding.sentUserMessageIds.addAll(pending.mapNotNull { it.id })
        return pending.joinToString("\n\n") { renderMessage(it) }
    }

    private fun renderFullContext(messages: List<UIMessage>): String = buildString {
        messages.forEach { message ->
            renderMessage(message).let { if (it.isNotBlank()) append(it).append("\n\n") }
        }
    }.trim()

    private fun renderMessage(message: UIMessage): String {
        val body = message.toText()
        if (body.isBlank()) return ""
        return when (message.role) {
            MessageRole.SYSTEM -> "<system>\n$body\n</system>"
            MessageRole.USER -> "<user>\n$body\n</user>"
            MessageRole.ASSISTANT -> "<assistant>\n$body\n</assistant>"
            MessageRole.TOOL -> "" // 工具结果由 agent 侧自行处理，不注入提示词
        }
    }

    private fun relativeCwd(workspaceCwd: String?): String {
        if (workspaceCwd.isNullOrBlank()) return ""
        return workspaceCwd
            .removePrefix(WorkspaceManager.ROOTFS_WORKSPACE_DIR)
            .trim('/')
    }

    companion object {
        private const val TAG = "AcpRuntime"

        /** Max transparent process-crash recoveries per prompt turn. */
        private const val MAX_RECOVERY_ATTEMPTS = 1
    }
}
