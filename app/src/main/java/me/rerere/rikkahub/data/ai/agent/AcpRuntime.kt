package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.agent.AcpSessionUpdate
import me.rerere.ai.agent.SessionUpdateBridge
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AgentPlatform
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceProcessRunner
import kotlin.uuid.Uuid

/**
 * Manages the ACP agent sub-process lifecycle for generation paths.
 *
 * Responsibilities:
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
) {
    private data class SessionBinding(
        val client: AcpAgentClient,
        val sessionId: String,
        val sentUserMessageIds: MutableSet<Uuid> = mutableSetOf(),
    )

    private val clients = mutableMapOf<String, AcpAgentClient>()
    private val sessionBindings = mutableMapOf<String, SessionBinding>()
    private val mutex = Mutex()

    /**
     * Streams a single assistant turn through the bound platform agent.
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

        val client = getOrCreateClient(model, platform, root, workspaceCwd)
        val binding = getOrCreateSession(client, platform, root, conversationId)
        val promptText = renderPrompt(messages, binding)

        Log.i(TAG, "prompting ${platform.name} session=${binding.sessionId}")
        val bridge = SessionUpdateBridge()
        client.prompt(binding.sessionId, promptText).collect { element ->
            element.toSessionUpdate()?.let { update ->
                bridge.translate(update)?.let { emit(it) }
            }
        }
        bridge.finish()?.let { emit(it) }
    }

    /** Closes every agent process and forgets all sessions (e.g. app teardown). */
    fun closeAll() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        sessionBindings.clear()
    }
    private suspend fun getOrCreateClient(
        model: Model,
        platform: AgentPlatform,
        root: String,
        workspaceCwd: String?,
    ): AcpAgentClient = mutex.withLock {
        val cli = environmentManager.cliCommand(platform, model.agentArguments)
        val env = model.agentEnvironment
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
            return@withLock SessionBinding(client, sessionId)
        }
        val key = "${root}|${platform.name}|$conversationId"
        sessionBindings[key]?.takeIf { it.client === client } ?: run {
            val sessionId = client.newSession(mcpServers = mcpServers).getOrThrow()
            SessionBinding(client, sessionId).also { sessionBindings[key] = it }
        }
    }

    private fun renderPrompt(messages: List<UIMessage>, binding: SessionBinding): String {
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

    private fun JsonElement.toSessionUpdate(): AcpSessionUpdate? = runCatching {
        val params = (this as? JsonObject)?.get("params")?.jsonObject ?: return null
        val update = params["update"] ?: return null
        json.decodeFromJsonElement(AcpSessionUpdate.serializer(), update)
    }.getOrNull()

    companion object {
        private const val TAG = "AcpRuntime"
    }
}
