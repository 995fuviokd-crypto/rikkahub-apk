package me.rerere.ai.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Agent Client Protocol (ACP) — JSON-RPC 2.0 over stdio message models.
 *
 * ACP standardizes communication between a client (this app) and a coding agent
 * (Codex CLI, Claude Code CLI, OpenCode CLI, DeepSeek Harness, …). The agent runs
 * as a sub-process; all requests are written to its stdin and responses/notifications
 * are read from its stdout as newline-delimited JSON-RPC 2.0 messages.
 *
 * Only the subset of the protocol needed to drive text conversations is modeled here.
 */

@Serializable
data class AcpMessage(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonElement? = null,
)

@Serializable
data class AcpImplementation(
    val name: String,
    val title: String,
    val version: String,
)

/** Capabilities the client (this app) advertises during initialization. */
@Serializable
data class AcpClientCapabilities(
    val auth: AcpAuthCapabilities? = null,
    val session: AcpClientSessionCapabilities? = null,
)

@Serializable
data class AcpAuthCapabilities(
    @SerialName("terminal") val terminal: JsonElement? = null,
)

@Serializable
data class AcpClientSessionCapabilities(
    val prompt: AcpClientPromptCapabilities? = null,
    val mcp: AcpClientMcpCapabilities? = null,
)

@Serializable
data class AcpClientPromptCapabilities(
    val image: JsonElement? = null,
    val audio: JsonElement? = null,
    @SerialName("embeddedContext") val embeddedContext: JsonElement? = null,
)

@Serializable
data class AcpClientMcpCapabilities(
    val stdio: JsonElement? = null,
    val http: JsonElement? = null,
)

/** A text content block used in prompts and in streamed agent output. */
@Serializable
data class AcpTextContent(
    val type: String = "text",
    val text: String,
)

/** session/new request parameters. */
@Serializable
data class AcpNewSessionParams(
    val cwd: String,
    val mcpServers: List<JsonElement> = emptyList(),
)

/** session/prompt request parameters (single text prompt). */
@Serializable
data class AcpPromptParams(
    val sessionId: String,
    val prompt: List<AcpTextContent>,
)

/**
 * The body of a session/update notification.
 *
 * The protocol defines a discriminated union over `sessionUpdate`. Modeled variants:
 * - `agent_message_chunk` / `message_update`: assistant text (v1/v2 shapes).
 * - `agent_thought_chunk`: reasoning text, bridged to RikkaHub's reasoning part.
 * - `tool_call` / `tool_call_update`: tool executions, bridged to server-tool parts
 *   so the chat UI can render live tool cards with input/output.
 * - `plan`: todo-list snapshot (desktop agents stream the evolving task plan here),
 *   bridged to a live server-tool card whose metadata carries the full entries list.
 * - `current_mode_update`: session mode switch (e.g. plan / acceptEdits modes).
 * Unknown variants are preserved as raw JSON and ignored by the stream bridge.
 */
@Serializable
data class AcpSessionUpdate(
    @SerialName("sessionUpdate") val sessionUpdate: String,
    val content: AcpTextContent? = null,
    val delta: List<AcpContentChunk>? = null,
    val messageId: String? = null,
    // --- tool_call / tool_call_update fields ---
    val toolCallId: String? = null,
    val title: String? = null,
    /** kind: read/edit/delete/move/search/execute/think/fetch/other */
    val kind: String? = null,
    /** status: pending/in_progress/completed/failed */
    val status: String? = null,
    val rawInput: JsonElement? = null,
    val rawOutput: JsonElement? = null,
    // --- plan fields ---
    val entries: List<AcpPlanEntry>? = null,
    // --- current_mode_update fields ---
    @SerialName("currentModeId") val currentModeId: String? = null,
)

/** One entry of a `plan` session update (todo list item). status: pending/in_progress/completed. */
@Serializable
data class AcpPlanEntry(
    val content: String,
    /** high | medium | low */
    val priority: String? = null,
    /** pending | in_progress | completed */
    val status: String? = null,
)

/** A single streamed content chunk inside a message_update delta array. */
@Serializable
data class AcpContentChunk(
    val type: String,
    val text: String? = null,
    val message: JsonElement? = null,
)

/**
 * A selectable option inside an agent-initiated `session/request_permission` request.
 * kind: allow_once | allow_always | reject_once | reject_always
 */
@Serializable
data class AcpPermissionOption(
    @SerialName("optionId") val optionId: String,
    val name: String,
    val kind: String? = null,
)

/** The `toolCall` field carried by a `session/request_permission` params. */
@Serializable
data class AcpToolCallInfo(
    @SerialName("toolCallId") val toolCallId: String? = null,
    val title: String? = null,
    val kind: String? = null,
    val rawInput: JsonElement? = null,
)

/** Params of the agent-initiated `session/request_permission` request. */
@Serializable
data class AcpPermissionRequest(
    val sessionId: String,
    val toolCall: AcpToolCallInfo? = null,
    val options: List<AcpPermissionOption> = emptyList(),
)

/**
 * Outcome written back in response to `session/request_permission`.
 * Either selects one of the offered options or cancels the tool call outright.
 */
@Serializable
data class AcpPermissionOutcome(
    val outcome: String,
    @SerialName("optionId") val optionId: String? = null,
) {
    companion object {
        fun selected(optionId: String) = AcpPermissionOutcome(outcome = "selected", optionId = optionId)
        val CANCELLED = AcpPermissionOutcome(outcome = "cancelled", optionId = null)
    }
}

/** Params of the agent-initiated `fs/read_text_file` request. */
@Serializable
data class AcpReadTextFileParams(
    val sessionId: String,
    val path: String,
    val line: Int? = null,
    val limit: Int? = null,
)

/** Params of the agent-initiated `fs/write_text_file` request. */
@Serializable
data class AcpWriteTextFileParams(
    val sessionId: String,
    val path: String,
    val content: String,
)
