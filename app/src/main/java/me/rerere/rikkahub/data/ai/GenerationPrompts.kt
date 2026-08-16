package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are relevant memories recalled for the current query via the memory system (scope-recall). You may reference them when answering. They persist across conversations and are automatically recalled when relevant.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("target", memory.target)
                    put("content", memory.content)
                    memory.summary?.let { put("summary", it) }
                    put("score", memory.score)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }
