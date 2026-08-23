package me.rerere.rikkahub.data.tavern

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import java.util.UUID
import kotlin.uuid.Uuid

/** 解析后的角色卡中间模型（spec 无关） */
data class TavernCard(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val systemPrompt: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val emoji: String = "🎭",
    val characterBookEntries: List<TavernBookEntry> = emptyList(),
)

/** 角色卡内嵌世界书条目 */
data class TavernBookEntry(
    val keys: List<String>,
    val content: String,
    val comment: String = "",
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
    val depth: Int = 4,
    val caseSensitive: Boolean = false,
)

/**
 * SillyTavern 角色卡 / 世界书的纯逻辑解析与转换。
 *
 * - 角色卡：chara_card_v2 / chara_card_v3 标准 spec，兼容 V1 顶层字段旧格式
 * - 世界书：SillyTavern world info JSON（entries 为对象 map）
 * 转换产物直接映射 RikkaHub 的 Assistant + Lorebook，随 Settings JSON 持久化。
 */
object TavernCardConverter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }


    fun isCharacterCardJson(jsonText: String): Boolean {
        val obj = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return false
        return obj["spec"]?.jsonPrimitive?.contentOrNull?.startsWith("chara_card") == true ||
            obj.containsKey("char_name") || // V1
            (obj["data"] as? JsonObject)?.containsKey("name") == true
    }

    /** 解析角色卡 JSON（V2/V3 spec 或 V1 兼容），失败抛 IllegalStateException */
    fun parseCard(jsonText: String): TavernCard {
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { error("角色卡不是合法 JSON: ${it.message}") }
        // V2/V3: {"spec": "chara_card_v2", "data": {...}}；V1: 顶层字段
        val data = (root["data"] as? JsonObject) ?: root

        val name = data.str("name", "char_name")
            ?: throw IllegalArgumentException("卡片缺少 name 字段")

        return TavernCard(
            name = name,
            description = data.str("description") ?: "",
            personality = data.str("personality", "personality") ?: "",
            scenario = data.str("scenario") ?: "",
            systemPrompt = data.str("system_prompt") ?: "",
            firstMes = data.str("first_mes") ?: "",
            mesExample = data.str("mes_example") ?: "",
            creatorNotes = (root["creator_notes"] ?: data["creator_notes"]).asStr() ?: "",
            tags = data["tags"]?.jsonArray?.mapNotNull { it.asStr() }.orEmpty(),
            emoji = data.str("emoji") ?: guessEmoji(name, data.str("description") ?: ""),
            characterBookEntries = parseBook(data["character_book"] as? JsonObject),
        )
    }

    private fun parseBook(book: JsonObject?): List<TavernBookEntry> {
        if (book == null) return emptyList()
        val entries = book["entries"] as? kotlinx.serialization.json.JsonArray
            ?: (book["entries"] as? JsonObject)?.values?.let { values ->
                // ST world info 的 entries 是 map；卡内嵌书是数组。两者都兼容
                return@let values.toList()
            }
            ?: return emptyList()
        return entries.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val content = e.str("content") ?: return@mapNotNull null
            val keys = e["keys"]?.jsonArray?.mapNotNull { it.asStr() }
                ?: e["key"]?.jsonArray?.mapNotNull { it.asStr() }
                ?: emptyList()
            TavernBookEntry(
                keys = keys,
                content = content,
                comment = e.str("comment", "name") ?: keys.firstOrNull().orEmpty(),
                enabled = e["enabled"]?.jsonPrimitive?.booleanOrNull ?: !(e["disable"]?.jsonPrimitive?.booleanOrNull ?: false),
                constant = e["constant"]?.jsonPrimitive?.booleanOrNull ?: false,
                position = mapPosition(e["position"]?.jsonPrimitive?.intOrNull),
                depth = e["extensions"]?.jsonObject?.get("depth")?.jsonPrimitive?.intOrNull
                    ?: e["injection_depth"]?.jsonPrimitive?.intOrNull ?: 4,
                caseSensitive = e["case_sensitive"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    /**
     * SillyTavern position int 映射：
     * 0 before_char -> 系统提示词前; 1 after_char -> 系统提示词后;
     * 2 top_anthropic / 3 at_depth 极浅层统一归聊天顶部/系统后，保守处理
     */
    private fun mapPosition(raw: Int?): InjectionPosition = when (raw) {
        0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        4 -> InjectionPosition.AT_DEPTH
        else -> InjectionPosition.AFTER_SYSTEM_PROMPT
    }

    /** 卡片 → Assistant（systemPrompt 组装遵循酒馆惯例：描述/人格/场景分段 + 示例对话 <START> 块） */
    fun toAssistant(card: TavernCard, background: String? = null): Assistant {
        val prompt = buildString {
            appendLine("You are roleplaying as ${card.name}.")
            appendLine()
            if (card.systemPrompt.isNotBlank()) {
                appendLine(card.systemPrompt)
                appendLine()
            }
            if (card.description.isNotBlank()) {
                appendLine("## Description of the character")
                appendLine(card.description)
                appendLine()
            }
            if (card.personality.isNotBlank()) {
                appendLine("## Personality of the character")
                appendLine(card.personality)
                appendLine()
            }
            if (card.scenario.isNotBlank()) {
                appendLine("## Scenario")
                appendLine(card.scenario)
                appendLine()
            }
            if (card.mesExample.isNotBlank()) {
                appendLine("## Example dialogues")
                appendLine("<START>")
                append(card.mesExample)
            }
        }
        return Assistant(
            id = Uuid.parse(UUID.randomUUID().toString()),
            name = card.name,
            avatar = Avatar.Emoji(card.emoji),
            systemPrompt = prompt.trim(),
            presetMessages = if (card.firstMes.isNotBlank()) listOf(UIMessage.assistant(card.firstMes)) else emptyList(),
            background = background,
        )
    }

    /** 卡片内嵌世界书 → Lorebook（与助手关联由调用方写入 settings） */
    fun cardToLorebook(card: TavernCard): Lorebook? {
        if (card.characterBookEntries.isEmpty()) return null
        return Lorebook(
            name = "${card.name} 的世界书",
            description = "导入自角色卡「${card.name}」内嵌 character_book",
            entries = card.characterBookEntries.map { e ->
                PromptInjection.RegexInjection(
                    name = e.comment.ifEmpty { e.keys.firstOrNull().orEmpty() },
                    enabled = e.enabled,
                    position = e.position,
                    injectDepth = e.depth,
                    content = e.content,
                    keywords = e.keys,
                    caseSensitive = e.caseSensitive,
                    constantActive = e.constant,
                    scanDepth = e.depth,
                )
            },
        )
    }

    /** SillyTavern 世界书 JSON（{ entries: { "0": {...} } }）→ Lorebook */
    fun parseWorldInfo(jsonText: String, fileName: String? = null): Lorebook {
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { error("世界书不是合法 JSON: ${it.message}") }
        val entriesObj = root["entries"] as? JsonObject
            ?: throw IllegalArgumentException("缺少 entries 字段，可能不是酒馆世界书格式")
        val entries = entriesObj.values.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val content = e.str("content") ?: return@mapNotNull null
            val keys = e["key"]?.jsonArray?.mapNotNull { it.asStr() } ?: emptyList()
            PromptInjection.RegexInjection(
                name = e.str("comment")?.ifEmpty { keys.firstOrNull().orEmpty() } ?: keys.firstOrNull().orEmpty(),
                enabled = !(e["disable"]?.jsonPrimitive?.booleanOrNull ?: false),
                priority = e["order"]?.jsonPrimitive?.intOrNull ?: 100,
                position = mapPosition(e["position"]?.jsonPrimitive?.intOrNull),
                injectDepth = e["depth"]?.jsonPrimitive?.intOrNull ?: 4,
                content = content,
                keywords = keys,
                useRegex = false,
                caseSensitive = e["caseSensitive"]?.jsonPrimitive?.booleanOrNull ?: false,
                scanDepth = e["scanDepth"]?.jsonPrimitive?.intOrNull ?: 4,
                constantActive = e["constant"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        if (entries.isEmpty()) throw IllegalArgumentException("世界书没有可用条目")
        return Lorebook(
            name = fileName?.removeSuffix(".json").orEmpty().ifEmpty { "导入的世界书" },
            description = "导入自 SillyTavern 世界书（${entries.size} 条目）",
            entries = entries,
        )
    }

    /** SillyTavern 预设中与 RikkaHub 助手可映射的采样参数 */
    data class TavernPreset(
        val name: String,
        val temperature: Float? = null,
        val topP: Float? = null,
        val maxTokens: Int? = null,
    )

    /**
     * 解析 SillyTavern 预设 JSON（采样参数集）。
     * 兼容字段别名：openai_max_tokens / genamt / max_tokens；top_p / topP。
     */
    fun parsePreset(jsonText: String): TavernPreset {
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { error("预设不是合法 JSON: ${it.message}") }
        fun num(vararg keys: String): Float? =
            keys.firstNotNullOfOrNull { k ->
                (root[k] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()
                    ?: (root[k] as? JsonPrimitive)?.doubleOrNull?.toFloat()
            }
        return TavernPreset(
            name = root.str("name") ?: "导入的预设",
            temperature = num("temperature", "temp"),
            topP = num("top_p", "topP", "top_p_"),
            maxTokens = num("openai_max_tokens", "genamt", "max_tokens", "maxTokens")?.toInt(),
        )
    }

    /**
     * 解析 SillyTavern 正则脚本（单个对象或脚本库数组均可）→ AssistantRegex 列表。
     * placement: 1=用户输入(USER) 2=AI输出(ASSISTANT)；markdownOnly → 仅视觉替换。
     */
    fun parseRegexScripts(jsonText: String): List<AssistantRegex> {
        val root = runCatching { json.parseToJsonElement(jsonText) }
            .getOrElse { error("正则脚本不是合法 JSON: ${it.message}") }
        val array = when {
            root is kotlinx.serialization.json.JsonArray -> root.jsonArray
            else -> listOf(root)
        }
        val result = mutableListOf<AssistantRegex>()
        for (el in array) {
            val obj = el as? JsonObject ?: continue
            if (obj.containsKey("scripts")) {
                // ST 正则库：{"scripts": [...]}
                obj["scripts"]?.jsonArray?.forEach { inner ->
                    (inner as? JsonObject)?.let { regexFromObject(it) }?.let { result.add(it) }
                }
            } else {
                regexFromObject(obj)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun regexFromObject(obj: JsonObject): AssistantRegex? {
        val find = obj.str("findRegex", "find_regex", "find") ?: return null
        val placementNumbers = obj["placement"]?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
            .orEmpty()
        val scopes = buildSet {
            if (placementNumbers.isEmpty() || 1 in placementNumbers) add(AssistantAffectScope.USER)
            if (2 in placementNumbers || 0 in placementNumbers) add(AssistantAffectScope.ASSISTANT)
        }.ifEmpty { setOf(AssistantAffectScope.ASSISTANT) }
        return AssistantRegex(
            id = Uuid.parse(java.util.UUID.randomUUID().toString()),
            name = obj.str("scriptName", "name") ?: find.take(20),
            enabled = !(obj["disabled"]?.jsonPrimitive?.booleanOrNull ?: false),
            findRegex = find,
            replaceString = obj.str("replaceString", "replace") ?: "",
            affectingScope = scopes,
            visualOnly = obj["markdownOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

    private fun guessEmoji(name: String, description: String): String {
        val text = (name + description).lowercase()
        return when {
            listOf("侦探", "detective", "holmes", "福尔摩斯", "推理").any { it in text } -> "🔍"
            listOf("老师", "导师", "教", "teacher", "tutor", "mentor").any { it in text } -> "🎓"
            listOf("医生", "健康", "doctor").any { it in text } -> "🩺"
            listOf("教练", "健身", "coach").any { it in text } -> "🏋️"
            listOf("猫", "cat").any { it in text } -> "🐱"
            listOf("狗", "dog").any { it in text } -> "🐶"
            listOf("机器人", "robot", "ai").any { it in text } -> "🤖"
            listOf("厨师", "料理", "chef", "cook").any { it in text } -> "👨‍🍳"
            listOf("游戏", "game").any { it in text } -> "🎮"
            else -> "🎭"
        }
    }
}

// kotlinx 的 JsonPrimitive 内容读取小工具集中在此，避免到处 import
private fun kotlinx.serialization.json.JsonElement?.asStr(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
