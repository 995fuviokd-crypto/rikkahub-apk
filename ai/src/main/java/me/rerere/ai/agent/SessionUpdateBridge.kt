package me.rerere.ai.agent

import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * Converts ACP `session/update` notifications into RikkaHub [StreamChunk] events.
 *
 * The ACP protocol streams agent output in several shapes across protocol versions:
 * - `agent_message_chunk` (v1): a single [AcpTextContent] in `update.content`.
 * - `message_update` (v2): a `delta` array whose entries carry `text` deltas for the
 *   assistant message.
 * - `agent_thought_chunk`: reasoning text, bridged to RikkaHub's reasoning part so the
 *   chat UI shows the agent's thinking like native reasoning models do.
 * - `tool_call` / `tool_call_update`: tool executions, bridged to server-tool parts so
 *   the chat UI renders live tool cards (command runs, file edits, searches…).
 *
 * Thread-safety: [SessionUpdateBridge] is single-shot — feed updates in order and do not
 * reuse the instance across conversations.
 */
class SessionUpdateBridge(
    private val messageId: String = "acp-agent",
) {
    private var textStarted = false
    private var reasoningStarted = false
    private val openToolCalls = mutableSetOf<String>()
    private var planCardOpen = false
    private var modeCardOpen = false

    /**
     * Translates a raw session/update notification into a list of [StreamChunk]s.
     * Empty when the update carries nothing renderable.
     */
    fun translate(update: AcpSessionUpdate): List<StreamChunk> = when (update.sessionUpdate) {
        "agent_message_chunk", "message_update" -> buildList {
            val text = extractText(update) ?: return@buildList
            if (!textStarted) {
                add(StreamChunk.TextStart(messageId))
                textStarted = true
            }
            add(StreamChunk.TextDelta(messageId, text))
        }

        "agent_thought_chunk" -> buildList {
            val text = update.content?.takeIf { it.type == "text" }?.text ?: return@buildList
            if (!reasoningStarted) {
                add(StreamChunk.ReasoningStart(id = messageId))
                reasoningStarted = true
            }
            add(StreamChunk.ReasoningDelta(id = messageId, text = text))
        }

        "tool_call" -> buildList {
            val id = update.toolCallId ?: return@buildList
            openToolCalls += id
            add(
                StreamChunk.ServerToolStart(
                    id = id,
                    toolName = toolLabel(update),
                    input = update.rawInput,
                    metadata = toolMetadata(update),
                )
            )
        }

        "tool_call_update" -> buildList {
            val id = update.toolCallId ?: return@buildList
            // 部分更新(如仅 locations 变化): 无状态字段时保持打开
            val status = update.status ?: return@buildList
            if (status in setOf("completed", "failed")) {
                openToolCalls -= id
                add(
                    StreamChunk.ServerToolEnd(
                        id = id,
                        input = update.rawInput,
                        output = update.rawOutput,
                        status = if (status == "completed") ServerToolStatus.COMPLETED else ServerToolStatus.FAILED,
                        metadata = toolMetadata(update),
                    )
                )
            }
        }

        "plan" -> buildList {
            val entries = update.entries?.takeIf { it.isNotEmpty() } ?: return@buildList
            planCardOpen = true
            add(
                StreamChunk.ServerToolStart(
                    id = PLAN_TOOL_ID,
                    toolName = "plan",
                    input = null,
                    metadata = planMetadata(entries),
                )
            )
        }

        "current_mode_update" -> buildList {
            val modeId = update.currentModeId?.takeIf { it.isNotBlank() } ?: return@buildList
            modeCardOpen = true
            add(
                StreamChunk.ServerToolStart(
                    id = MODE_TOOL_ID,
                    toolName = modeId,
                    input = null,
                    metadata = buildJsonObject {
                        put("kind", "mode")
                        put("mode_id", modeId)
                    },
                )
            )
        }

        else -> emptyList()
    }

    /**
     * Closes every still-open stream state. Called when the session finishes so the UI
     * never keeps a spinner on an unterminated reasoning/tool part.
     */
    fun finish(): List<StreamChunk> = buildList {
        if (reasoningStarted) {
            add(StreamChunk.ReasoningEnd(id = messageId))
            reasoningStarted = false
        }
        openToolCalls.toList().forEach { id ->
            add(
                StreamChunk.ServerToolEnd(
                    id = id,
                    status = ServerToolStatus.COMPLETED,
                )
            )
        }
        openToolCalls.clear()
        if (planCardOpen) {
            add(StreamChunk.ServerToolEnd(id = PLAN_TOOL_ID, status = ServerToolStatus.COMPLETED))
            planCardOpen = false
        }
        if (modeCardOpen) {
            add(StreamChunk.ServerToolEnd(id = MODE_TOOL_ID, status = ServerToolStatus.COMPLETED))
            modeCardOpen = false
        }
        if (textStarted) {
            add(StreamChunk.TextEnd(messageId))
            textStarted = false
        }
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

    /** Human-readable tool label: prefer the title ("Read file src/main.kt"), fall back to kind. */
    private fun toolLabel(update: AcpSessionUpdate): String =
        update.title?.takeIf { it.isNotBlank() }
            ?: update.kind?.replaceFirstChar { it.uppercase() }
            ?: "tool"

    /** Attaches kind/status for the UI to style tool cards (running spinner vs result). */
    private fun toolMetadata(update: AcpSessionUpdate): JsonObject = buildJsonObject {
        update.kind?.let { put("kind", it) }
        update.status?.let { put("acp_status", it) }
    }

    /**
     * Serializes the plan entries into the card metadata. Repeated `plan` updates reuse
     * the same tool id, so the handler refreshes the single live card in place and the
     * UI always renders the latest snapshot.
     */
    private fun planMetadata(entries: List<AcpPlanEntry>): JsonObject = buildJsonObject {
        put("kind", "plan")
        put(
            "plan_entries",
            buildJsonArray {
                entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("content", entry.content)
                            entry.priority?.let { put("priority", it) }
                            entry.status?.let { put("status", it) }
                        }
                    )
                }
            }
        )
    }

    companion object {
        /** Fixed server-tool id for the live plan (todo list) card. */
        const val PLAN_TOOL_ID = "acp-plan"

        /** Fixed server-tool id for the session-mode indicator card. */
        const val MODE_TOOL_ID = "acp-mode"
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
