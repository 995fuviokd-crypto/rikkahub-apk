package me.rerere.rikkahub.data.datastore.migration

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "SettingsJsonSanitizer"

// 与 ProviderSetting 密封类的 @SerialName 保持同步
private val KNOWN_PROVIDER_TYPES = setOf("openai", "google", "claude")

// 与 ModelType / Modality / ModelAbility 枚举保持同步;
// 列表元素不受 coerceInputValues 保护, 必须显式清洗 (PC 端可能导出 AUDIO/VIDEO/DOCUMENT)
private val KNOWN_MODEL_TYPES = setOf("CHAT", "IMAGE", "VIDEO", "EMBEDDING")
private val KNOWN_MODALITIES = setOf("TEXT", "IMAGE")
private val KNOWN_MODEL_ABILITIES = setOf("TOOL", "REASONING")

// 与 BuiltInTools 密封类的 @SerialName 保持同步
private val KNOWN_BUILTIN_TOOLS = setOf("search", "url_context", "image_generation")

// 与 SearchServiceOptions 密封类子类的 @SerialName 保持同步
private val KNOWN_SEARCH_SERVICE_TYPES = setOf(
    "bing_local", "zhipu", "doubao", "tavily", "exa", "searxng",
    "linkup", "brave", "metaso", "ollama", "perplexity", "firecrawl",
    "jina", "bocha", "rikkahub", "grok", "tinyfish", "serper", "custom_js",
)

// 与 UIMessagePart 密封类子类的 @SerialName 保持同步
private val KNOWN_MESSAGE_PART_TYPES = setOf(
    "text", "image", "video", "audio", "document", "reasoning",
    "search", "tool_call", "tool_result", "server_tool", "tool",
)

// 与 UIMessageAnnotation 密封类子类的 @SerialName 保持同步
private val KNOWN_ANNOTATION_TYPES = setOf("url_citation")

// Avatar 无 @SerialName, 判别符为全限定类名; PC 端内部使用短名, 导入时映射回 FQN
private const val AVATAR_DUMMY = "me.rerere.rikkahub.data.model.Avatar.Dummy"
private const val AVATAR_EMOJI = "me.rerere.rikkahub.data.model.Avatar.Emoji"
private const val AVATAR_IMAGE = "me.rerere.rikkahub.data.model.Avatar.Image"
private val PC_AVATAR_TYPE_TO_ANDROID = mapOf(
    "dummy" to AVATAR_DUMMY,
    "emoji" to AVATAR_EMOJI,
    "image" to AVATAR_IMAGE,
    "url" to AVATAR_IMAGE,
)
private val KNOWN_AVATAR_TYPES = setOf(AVATAR_DUMMY, AVATAR_EMOJI, AVATAR_IMAGE)

/**
 * 对备份文件中的 settings.json 做导入前清洗, 保证跨端备份 (如 RikkaHub PC) 的兼容性。
 *
 * kotlinx.serialization 的 ignoreUnknownKeys 只能忽略未知字段名; 以下情况会直接抛出
 * SerializationException 导致整个 Settings 恢复失败:
 * 1. 密封类收到未知判别符 (providers/searchServices/models[].tools/message parts 等)
 * 2. 列表元素中的未知枚举值 (inputModalities/outputModalities/abilities), 不受
 *    coerceInputValues 保护
 * 3. 显式 null 赋给非空字段 (由 Json.coerceInputValues 处理, 此处不重复)
 *
 * 清洗只做删除与判别符映射, 不修改任何合法数据。
 */
object SettingsJsonSanitizer {

    /**
     * 清洗 settings JSON 字符串。若发生异常则返回原始 JSON, 不中断恢复流程。
     */
    fun sanitize(settingsJson: String): String {
        return runCatching {
            val root = JsonInstant.parseToJsonElement(settingsJson).jsonObject.toMutableMap()

            sanitizeProviders(root)
            sanitizeAssistants(root)
            sanitizeSearchServices(root)

            JsonInstant.encodeToString(JsonObject(root))
        }.onFailure {
            Log.e(TAG, "sanitize: Failed to sanitize settings JSON, using original", it)
        }.getOrDefault(settingsJson)
    }

    private fun sanitizeProviders(root: MutableMap<String, JsonElement>) {
        val providers = root["providers"] as? JsonArray ?: return
        root["providers"] = JsonArray(providers.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj.discriminatorOrNull() !in KNOWN_PROVIDER_TYPES) {
                Log.w(TAG, "sanitizeProviders: drop provider with unknown type=${obj.discriminatorOrNull()}")
                return@mapNotNull null
            }
            val mutable = obj.toMutableMap()
            sanitizeModels(mutable)
            JsonObject(mutable)
        })
    }

    private fun sanitizeModels(provider: MutableMap<String, JsonElement>) {
        val models = provider["models"] as? JsonArray ?: return
        provider["models"] = JsonArray(models.map { element ->
            val model = element as? JsonObject ?: return@map element
            val mutable = model.toMutableMap()

            // 属性级枚举有默认值兜底, 删掉非法值让其走默认
            (mutable["type"] as? JsonPrimitive)?.let { type ->
                if (type.content !in KNOWN_MODEL_TYPES) mutable.remove("type")
            }

            // List<Modality> / List<ModelAbility> 元素级枚举必须过滤, 空模态回退 TEXT 默认
            filterEnumList(mutable, "inputModalities", KNOWN_MODALITIES, fallback = listOf("TEXT"))
            filterEnumList(mutable, "outputModalities", KNOWN_MODALITIES, fallback = listOf("TEXT"))
            filterEnumList(mutable, "abilities", KNOWN_MODEL_ABILITIES)

            // Set<BuiltInTools> 是多态序列化, 未知 type 会炸整个 Settings
            filterDiscriminatorList(mutable, "tools", KNOWN_BUILTIN_TOOLS)

            JsonObject(mutable)
        })
    }

    private fun sanitizeAssistants(root: MutableMap<String, JsonElement>) {
        val assistants = root["assistants"] as? JsonArray ?: return
        root["assistants"] = JsonArray(assistants.map { element ->
            val assistant = element as? JsonObject ?: return@map element
            val mutable = assistant.toMutableMap()
            sanitizeAvatar(mutable)
            sanitizePresetMessages(mutable)
            JsonObject(mutable)
        })
    }

    private fun sanitizeAvatar(assistant: MutableMap<String, JsonElement>) {
        val avatar = assistant["avatar"] as? JsonObject ?: return
        val rawType = (avatar["type"] as? JsonPrimitive)?.content ?: return
        if (rawType in KNOWN_AVATAR_TYPES) return

        val mappedType = PC_AVATAR_TYPE_TO_ANDROID[rawType] ?: run {
            Log.w(TAG, "sanitizeAvatar: unknown avatar type=$rawType, fallback to Dummy")
            AVATAR_DUMMY
        }
        val mutable = avatar.toMutableMap()
        mutable["type"] = JsonPrimitive(mappedType)
        assistant["avatar"] = JsonObject(mutable)
    }

    private fun sanitizePresetMessages(assistant: MutableMap<String, JsonElement>) {
        val presetMessages = assistant["presetMessages"] as? JsonArray ?: return
        assistant["presetMessages"] = JsonArray(presetMessages.map { message ->
            val obj = message as? JsonObject ?: return@map message
            val mutable = obj.toMutableMap()
            filterDiscriminatorList(mutable, "parts", KNOWN_MESSAGE_PART_TYPES)
            filterDiscriminatorList(mutable, "annotations", KNOWN_ANNOTATION_TYPES)
            JsonObject(mutable)
        })
    }

    private fun sanitizeSearchServices(root: MutableMap<String, JsonElement>) {
        val services = root["searchServices"] as? JsonArray ?: return
        val cleaned = services.filter { it.discriminatorOrNull() in KNOWN_SEARCH_SERVICE_TYPES }
        if (cleaned.size == services.size) return

        Log.w(TAG, "sanitizeSearchServices: dropped ${services.size - cleaned.size} unknown search services")
        if (cleaned.isEmpty()) {
            // 全部非法时删键, 让 Settings 走默认值, 避免空列表与越界选中下标
            root.remove("searchServices")
            root.remove("searchServiceSelected")
            return
        }
        root["searchServices"] = JsonArray(cleaned)

        // 选中下标重定位: 夹取到过滤后的有效范围
        val selected = (root["searchServiceSelected"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (selected != null) {
            root["searchServiceSelected"] = JsonPrimitive(selected.coerceIn(0, cleaned.size - 1))
        }
    }

    // 过滤多态元素数组 (按 "type" 判别符), 无变化时保留原引用
    private fun filterDiscriminatorList(map: MutableMap<String, JsonElement>, key: String, allowed: Set<String>) {
        val list = map[key] as? JsonArray ?: return
        val filtered = list.filter { it.discriminatorOrNull() in allowed }
        if (filtered.size != list.size) map[key] = JsonArray(filtered)
    }

    // 过滤纯字符串枚举数组, 全部非法且提供 fallback 时使用 fallback
    private fun filterEnumList(map: MutableMap<String, JsonElement>, key: String, allowed: Set<String>, fallback: List<String>? = null) {
        val list = map[key] as? JsonArray ?: return
        val filtered = list.filter { it is JsonPrimitive && it.content in allowed }
        if (filtered.size == list.size) return
        val result: List<JsonElement> =
            if (filtered.isEmpty() && fallback != null) fallback.map { JsonPrimitive(it) }
            else filtered
        map[key] = JsonArray(result)
    }

    private fun JsonElement.discriminatorOrNull(): String? =
        ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.content
}
