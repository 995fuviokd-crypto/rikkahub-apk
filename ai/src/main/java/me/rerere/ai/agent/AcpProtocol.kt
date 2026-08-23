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
 * The protocol defines a discriminated union over `sessionUpdate`. Only the variants
 * relevant for text chat are modeled; unknown variants are preserved as raw JSON and
 * ignored by the stream bridge.
 */
@Serializable
data class AcpSessionUpdate(
    @SerialName("sessionUpdate") val sessionUpdate: String,
    val content: AcpTextContent? = null,
    val delta: List<AcpContentChunk>? = null,
    val messageId: String? = null,
)

/** A single streamed content chunk inside a message_update delta array. */
@Serializable
data class AcpContentChunk(
    val type: String,
    val text: String? = null,
    val message: JsonElement? = null,
)
