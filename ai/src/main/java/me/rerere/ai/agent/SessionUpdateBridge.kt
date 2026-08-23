package me.rerere.ai.agent

import me.rerere.ai.ui.StreamChunk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts ACP `session/update` notifications into RikkaHub [StreamChunk] events.
 *
 * The ACP protocol streams agent output in several shapes across protocol versions:
 * - `agent_message_chunk` (v1): a single [AcpTextContent] in `update.content`.
 * - `message_update` (v2): a `delta` array whose entries carry `text` deltas for the
 *   assistant message. The `text` field on a [AcpContentChunk] holds the delta.
 *
 * The bridge normalizes both shapes into [StreamChunk.TextDelta] events with a stable
 * id so [me.rerere.ai.ui.StreamChunkHandler] can merge them into one assistant message.
 *
 * Thread-safety: [SessionUpdateBridge] is single-shot — feed updates in order and do not
 * reuse the instance across conversations.
 */
class SessionUpdateBridge(
    private val messageId: String = "acp-agent",
) {
    private var started = false

    /**
     * Translates a raw session/update notification into a [StreamChunk], or `null`
     * when the update carries no assistant text (e.g. usage updates, tool-call updates).
     */
    fun translate(update: AcpSessionUpdate): StreamChunk? {
        val text = extractText(update) ?: return null
        return when (update.sessionUpdate) {
            "agent_message_chunk" -> {
                val current = if (started) StreamChunk.TextDelta(messageId, text) else {
                    started = true
                    StreamChunk.TextStart(messageId)
                }
                started = true
                current
            }
            "message_update" -> {
                val current = if (started) StreamChunk.TextDelta(messageId, text) else {
                    started = true
                    StreamChunk.TextStart(messageId)
                }
                started = true
                current
            }
            else -> null
        }
    }

    /** Emits the closing [StreamChunk.TextEnd] once. Called when the session finishes. */
    fun finish(): StreamChunk? =
        if (started) {
            started = false
            StreamChunk.TextEnd(messageId)
        } else {
            null
        }

    private fun extractText(update: AcpSessionUpdate): String? {
        return when (update.sessionUpdate) {
            "agent_message_chunk" -> update.content?.takeIf { it.type == "text" }?.text
            "message_update" -> update.delta
                ?.filter { it.type == "text_delta" }
                ?.mapNotNull { it.text }
                ?.joinToString("")
                ?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}

/**
 * Best-effort extraction of the final assistant text from a `session/end` update's
 * `summary` (or the last `agent_message_chunk`), used when building a non-streaming reply.
 */
fun extractFinalText(update: AcpSessionUpdate): String? {
    if (update.sessionUpdate != "session/end") return null
    val summary = update.delta
        ?.filter { it.type == "text" }
        ?.mapNotNull { it.text }
        ?.joinToString("")
    return summary?.takeIf { it.isNotEmpty() }
}

internal fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.content
