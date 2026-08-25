package me.rerere.rikkahub.data.st

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import kotlin.uuid.Uuid

/**
 * SillyTavern 角色卡解析核心（V1 扁平 / V2 / V3 spec），纯 Kotlin 无 Android 依赖。
 *
 * - V2/V3: {"spec":"chara_card_v2","data":{...,"character_book":{...}}}
 * - V1:    {"name":...,"description":...,"first_mes":...} 顶层扁平结构
 *
 * 世界书映射：character_book.entries -> Lorebook(entries: RegexInjection)
 * keys -> keywords、content -> content、constant -> constantActive、disable 取反为 enabled
 */
object CharacterCardParser {

    data class ParsedCard(
        val assistant: Assistant,
        val lorebook: Lorebook?,
    )

    class ParseException(message: String) : Exception(message)

    fun parse(json: JsonObject): ParsedCard {
        // V1 扁平卡没有 data 包裹，字段在根对象；V2/V3 统一取 data
        val data = json["data"] as? JsonObject ?: json
        val name = data.str("name") ?: throw ParseException("missing name field")
        val description = data.str("description") ?: ""
        val personality = data.str("personality") ?: ""
        val scenario = data.str("scenario") ?: ""
        val firstMes = data.str("first_mes") ?: ""
        val systemPromptField = data.str("system_prompt") ?: ""

        val prompt = buildString {
            appendLine("You are roleplaying as $name.")
            appendLine()
            if (systemPromptField.isNotBlank()) {
                appendLine(systemPromptField)
                appendLine()
            }
            appendLine("## Description of the character")
            appendLine(description.ifBlank { "Empty" })
            appendLine()
            appendLine("## Personality of the character")
            appendLine(personality.ifBlank { "Empty" })
            appendLine()
            appendLine("## Scenario")
            append(scenario.ifBlank { "Empty" })
        }

        val lorebook = (data["character_book"] as? JsonObject)?.let { parseLorebook(name, it) }

        val assistant = Assistant(
            name = name,
            presetMessages = if (firstMes.isNotBlank()) listOf(UIMessage.assistant(firstMes)) else emptyList(),
            systemPrompt = prompt,
        )
        return ParsedCard(assistant, lorebook)
    }

    private fun parseLorebook(cardName: String, book: JsonObject): Lorebook {
        val entriesArray = book["entries"] as? JsonArray
        val injections = entriesArray?.mapIndexedNotNull { index, element ->
            val entry = element as? JsonObject ?: return@mapIndexedNotNull null
            val content = entry.str("content") ?: return@mapIndexedNotNull null
            val keys = (entry["keys"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            PromptInjection.RegexInjection(
                id = Uuid.random(),
                name = entry.str("comment") ?: keys.firstOrNull() ?: "Entry ${index + 1}",
                keywords = keys,
                content = content,
                constantActive = entry.bool("constant") ?: false,
                enabled = !(entry.bool("disable") ?: false),
                caseSensitive = entry.bool("case_sensitive") ?: false,
                priority = entry.int("insertion_order") ?: 0,
            )
        } ?: emptyList()

        return Lorebook(
            name = cardName,
            description = book.str("name") ?: "",
            entries = injections,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
