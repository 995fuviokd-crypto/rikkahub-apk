package me.rerere.rikkahub.data.cordis

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.session.Session
import me.rerere.rikkahub.data.session.SessionEvent

/**
 * 从 config 解析目标模型：优先 config["modelId"]（UUID 字符串），
 * 无效或缺失时回退到当前聊天模型。返回 null 表示无可用模型。
 */
internal fun resolveModel(config: JsonObject, settings: Settings): Model? {
    val modelIdStr = config["modelId"]?.jsonPrimitive?.content
    val byId = modelIdStr?.let { id ->
        runCatching { settings.findModelById(Uuid.parse(id)) }.getOrNull()
    }
    return byId ?: settings.getCurrentChatModel()
}

/** 构造主机侧一次文本生成的默认参数（对齐 backgroundTextGenerationParams）。 */
internal fun buildGenerationParams(model: Model): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = ReasoningLevel.AUTO,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

internal fun TokenUsage.toSeamJson(): JsonObject = buildJsonObject {
    put("promptTokens", promptTokens)
    put("completionTokens", completionTokens)
    put("cachedTokens", cachedTokens)
    put("totalTokens", totalTokens)
}

/**
 * `llm` 能力缝真实实现：把 ProviderManager + SettingsStore 适配为 [LlmSeam]。
 *
 * 供 Cordis 插件（及面板 CordisBridge）在宿主真实模型上执行推理。
 */
class HostLlmSeam(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
) : LlmSeam {
    override suspend fun infer(config: JsonObject, messages: List<UIMessage>): LlmSeamResult {
        val settings = settingsStore.settingsFlow.first()
        val model = resolveModel(config, settings)
            ?: throw IllegalStateException("no model available for llm seam")
        val providerSetting = model.findProvider(settings.providers)
            ?: throw IllegalStateException("no provider for model ${model.modelId}")
        val provider = providerManager.getProviderByType(providerSetting)
        val result = provider.generateText(providerSetting, messages, buildGenerationParams(model))
        return LlmSeamResult(
            output = listOf(result.message),
            usage = result.usage?.toSeamJson(),
            provider = providerSetting.name,
            model = result.model,
        )
    }
}

/**
 * `tools` 能力缝真实实现：独立工具注册表 + `tools/change` 事件派发。
 */
class HostToolsSeam(
    private val eventBus: CordisEventBus,
) : ToolsSeam {
    private val lock = Any()
    private val defs = linkedMapOf<String, ToolSeamDefinition>()

    override fun register(tool: ToolSeamDefinition): Boolean {
        val added = synchronized(lock) {
            if (defs.containsKey(tool.name)) return false
            defs[tool.name] = tool
            true
        }
        if (added) notifyChanged()
        return true
    }

    override fun unregister(name: String): Boolean {
        val removed = synchronized(lock) { defs.remove(name) != null }
        if (removed) notifyChanged()
        return removed
    }

    override fun definitions(): List<ToolSeamDefinition> = synchronized(lock) { defs.values.toList() }

    override fun get(name: String): ToolSeamDefinition? = synchronized(lock) { defs[name] }

    override fun notifyChanged() {
        val names = definitions().map { it.name }
        runBlocking {
            eventBus.emit(
                CordisEvent(
                    name = "tools/change",
                    payload = buildJsonObject {
                        put("tools", names.joinToString(","))
                        put("count", names.size)
                    },
                )
            )
        }
    }
}

/**
 * `sessions` 能力缝真实实现：维护内存事件源会话日志。
 *
 * [append] 反序列化 SessionEvent JSON 追加；[rebuildContext] 从日志派生上下文。
 * 与 [me.rerere.rikkahub.data.session.SessionEventRepository]（Room 持久化）互补：
 * 本实现面向插件运行时的轻量会话组装，持久化由 repository 承担。
 */
class HostSessionsSeam : SessionsSeam {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var current: Session = Session()

    fun bind(initial: Session) {
        current = initial
    }

    fun snapshot(): Session = current

    override suspend fun append(event: JsonObject) {
        val sessionEvent = json.decodeFromString<SessionEvent>(event.toString())
        current = current.append(sessionEvent)
    }

    override suspend fun rebuildContext(): List<UIMessage> =
        Session.deriveHistory(current)
}

/**
 * `systemPrompt` 能力缝真实实现：片段注册 + 按 position 顺序组装。
 */
class HostSystemPromptSeam : SystemPromptSeam {
    private data class Fragment(val id: String, val position: Int, val content: () -> String)

    private val fragments = linkedMapOf<String, Fragment>()

    override fun addFragment(id: String, position: Int, content: () -> String): Int {
        fragments[id] = Fragment(id, position, content)
        return fragments.size
    }

    override fun removeFragment(id: String) {
        fragments.remove(id)
    }

    override suspend fun assemble(): String =
        fragments.values
            .sortedWith(compareBy({ it.position }))
            .joinToString("\n\n") { it.content() }
}