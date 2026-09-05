package me.rerere.rikkahub.data.plugin

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.cordis.CordisKernel
import me.rerere.rikkahub.data.cordis.LlmSeam
import me.rerere.rikkahub.data.cordis.ToolsSeam
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlin.uuid.Uuid

/**
 * 注入到 Cordis PANEL 插件 WebView 的 JS 桥接（对象名 CordisBridge）。
 *
 * 与 PluginJsBridge 互补：PluginJsBridge 提供脚本工具调用与数据沙箱，
 * CordisJsBridge 提供 Cordis 能力缝访问（llm/tools/sessions/fs 等）。
 *
 * 重依赖（AgentHost/ConversationRepository/ChatService）以惰性提供者注入：
 * 桥构造零成本，首次真实调用才解析；解析失败经 seamCall 外层 runCatching
 * 转为结构化错误返回 JS 侧，宿主不崩溃。
 *
 * 面板 JS 侧调用示例：
 * ```
 * // 同步通道（兼容保留）
 * const r = JSON.parse(window.CordisBridge.seamCall("llm", "infer", JSON.stringify({...})));
 * // 异步通道（R3.1）：立即返回 callId，结果经 onResult 回推
 * const a = JSON.parse(window.CordisBridge.seamCallAsync("llm", "infer", JSON.stringify({...})));
 * if (a.ok) {
 *   window.__cordisPending = window.__cordisPending || {};
 *   window.__cordisPending[a.callId] = { resolve, reject };
 * } else { see a.reason for failure details }
 * // 宿主回推：CordisBridge.onResult(callId, jsonString)
 * window.CordisBridge.onResult = function(callId, json) {
 *   const p = window.__cordisPending?.[callId]; if (!p) return;
 *   delete window.__cordisPending[callId]; p.resolve(JSON.parse(json));
 * };
 * ```
 */
class CordisJsBridge(
    private val pluginId: String,
    private val kernel: CordisKernel,
    private val capabilities: Set<String>,
    private val agentHost: (() -> me.rerere.rikkahub.data.agent.AgentHost)? = null,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore? = null,
    private val conversationRepo: (() -> me.rerere.rikkahub.data.repository.ConversationRepository)? = null,
    private val chatService: (() -> me.rerere.rikkahub.service.ChatService)? = null,
    private val eventBus: CordisHostEventBus? = null,
    private val asyncScope: CoroutineScope? = null,
    private val resultDispatcher: ((js: String) -> Unit)? = null,
) {
    /** 当前事件订阅句柄（events.subscribe 管理，release/unsubscribe 清理）。 */
    @Volatile
    private var eventSubscription: CordisHostEventBus.Subscription? = null
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val callSeq = java.util.concurrent.atomic.AtomicLong(0)
    }

    /**
     * 通用能力缝调用：seamCall(seamName, method, argsJson) → JSON 字符串。
     *
* 路由表：
             * - llm.infer → LlmSeam.infer
             * - tools.list → ToolsSeam.definitions
             * - tools.execute → ToolsSeam.get + execute
             * - agent.run → AgentHost.run（面板触发一轮 agent 循环）
             * - sessions.list/get/send → 宿主会话读写
             * - events.poll {since} → 宿主事件增量拉取（生成更新/结束）
             *
             * 未声明能力缝时返回 { ok: false, message: "capability not declared" }。
             */
    @JavascriptInterface
    fun seamCall(seamName: String, method: String, argsJson: String): String {
        return runCatching {
            if (seamName !in capabilities) {
                return encodeReason("not_declared", "capability '$seamName' not declared for plugin '$pluginId'")
            }
            when (seamName) {
                "llm" -> handleLlm(method, argsJson)
                "tools" -> handleTools(method, argsJson)
                "agent" -> handleAgent(method, argsJson)
                "sessions" -> handleSessions(method, argsJson)
                "events" -> handleEvents(method, argsJson)
                else -> encodeReason("unknown_seam", "unknown seam: $seamName")
            }
        }.getOrElse { e ->
            encodeReason("execution_failed", e.message ?: "CordisJsBridge error")
        }
    }

    /**
     * 异步能力缝调用（R3.1）：立即返回 {ok, callId}，实际执行在宿主 IO 协程，
     * 完成后经 [resultDispatcher] 回推 `CordisBridge.onResult(callId, json)`。
     *
     * - 能力未声明 → 同步返回 {ok:false, reason:"not_declared"}（无异步副作用）
     * - asyncScope/resultDispatcher 缺席 → {ok:false, reason:"unimplemented"}
     * - 执行异常 → 仍回推 {ok:false, reason:"execution_failed"}，JS 侧 Promise 正常 reject
     *
     * 旧 [seamCall] 保留为同步兼容通道。
     */
    @JavascriptInterface
    fun seamCallAsync(seamName: String, method: String, argsJson: String): String {
        if (seamName !in capabilities) {
            return encodeReason("not_declared", "capability '$seamName' not declared for plugin '$pluginId'")
        }
        val scope = asyncScope ?: return encodeReason("unimplemented", "async bridge scope not available")
        if (resultDispatcher == null) {
            return encodeReason("unimplemented", "async result dispatcher not available")
        }
        val callId = "call-${callSeq.incrementAndGet()}"
        scope.launch(Dispatchers.IO) {
            val result = runCatching { seamCall(seamName, method, argsJson) }
                .getOrElse { e -> encodeReason("execution_failed", e.message ?: "async seam call failed") }
            dispatchResult(callId, result)
        }
        return buildJsonObject {
            put("ok", true)
            put("callId", callId)
        }.toString()
    }

    /** 把结果编码为 JS 表达式并经 [resultDispatcher] 回推；dispatcher 异常吞掉（不崩宿主）。 */
    internal fun dispatchResult(callId: String, resultJson: String) {
        val jsExpr = "window.CordisBridge.onResult(" +
            json.encodeToString(String.serializer(), callId) + ", " +
            json.encodeToString(String.serializer(), resultJson) + ")"
        runCatching { resultDispatcher?.invoke(jsExpr) }
            .onFailure { android.util.Log.w("CordisJsBridge", "dispatch result failed: callId=$callId", it) }
    }

    private fun handleAgent(method: String, argsJson: String): String {
        if (method != "run") return encode(false, "unknown agent method: $method")
        val host = resolve(agentHost, "agent host") ?: return encodeReason("unimplemented", "agent host not available")
        val args = json.parseToJsonElement(argsJson).let { it as? JsonObject ?: buildJsonObject { } }
        val prompt = args["prompt"]?.jsonPrimitive?.content
        if (prompt == null) return encode(false, "missing prompt")
        return runBlocking {
            val session = host.run(prompt)
            buildJsonObject {
                put("ok", true)
                put("turnCount", session.turnCount())
                put("events", session.length())
                val last = session.events
                    .filterIsInstance<me.rerere.rikkahub.data.session.SessionEvent.AssistantMessage>()
                    .lastOrNull()
                put("assistant", last?.content.orEmpty())
            }.toString()
        }
    }

    /**
     * `llm` 缝：文本推理。
     *
     * 参数（R2.1 全参数透传）：
     * - messages: [{role: "user"|"assistant"|"system", text: "..."}] 缺省为 [{"role":"user","text":config.prompt}]
     * - model: 模型 id（Uuid 字符串，缺省取当前聊天模型）
     * - systemPrompt: 系统提示词（合并进 config 供 seam 实现/后续 pipeline 消费）
     * - 其余键（temperature 等）原样透传进 config
     */
    private fun handleLlm(method: String, argsJson: String): String {
        val seam = kernel.rootContext.get("llm") as? LlmSeam
            ?: return encodeReason("unimplemented", "llm seam not available")
        if (method != "infer") return encode(false, "unknown llm method: $method")
        return runBlocking {
            val args = json.parseToJsonElement(argsJson).let { it as? JsonObject ?: buildJsonObject { } }
            val messages = parseMessages(args)
            val config = buildConfig(args)
            val result = seam.infer(config, messages)
            buildJsonObject {
                put("ok", true)
                put("output", result.output.joinToString("") { it.toText() })
                put("model", result.model)
                put("provider", result.provider)
            }.toString()
        }
    }

    /** 把 JS 侧 messages 数组解析为 UIMessage；缺省回退单条 user 文本（prompt 键）。 */
    private fun parseMessages(args: JsonObject): List<me.rerere.ai.ui.UIMessage> {
        val arr = args["messages"] as? kotlinx.serialization.json.JsonArray
        val parsed = arr?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val role = runCatching {
                me.rerere.ai.core.MessageRole.valueOf(
                    obj["role"]?.jsonPrimitive?.content?.uppercase() ?: "USER"
                )
            }.getOrDefault(me.rerere.ai.core.MessageRole.USER)
            val text = obj["text"]?.jsonPrimitive?.content ?: return@mapNotNull null
            me.rerere.ai.ui.UIMessage(
                role = role,
                parts = listOf(UIMessagePart.Text(text)),
            )
        }.orEmpty()
        if (parsed.isNotEmpty()) return parsed
        val prompt = args["prompt"]?.jsonPrimitive?.content
            ?: args["text"]?.jsonPrimitive?.content
            ?: return emptyList()
        return listOf(
            me.rerere.ai.ui.UIMessage(
                role = me.rerere.ai.core.MessageRole.USER,
                parts = listOf(UIMessagePart.Text(prompt)),
            )
        )
    }

    /** 透传 config：model/modelId/systemPrompt 合并，其余键原样保留。 */
    private fun buildConfig(args: JsonObject): JsonObject = buildJsonObject {
        args.forEach { (k, v) ->
            if (k !in setOf("messages", "prompt", "text")) put(k, v)
        }
        // model/modelId 归一化为 seam 侧约定的 modelId 键
        val model = args["model"]?.jsonPrimitive?.content
        val modelId = args["modelId"]?.jsonPrimitive?.content
        if (model != null && args["modelId"] == null) put("modelId", model)
        if (modelId != null) put("modelId", modelId)
    }

    private fun handleTools(method: String, argsJson: String): String {
        val seam = kernel.rootContext.get("tools") as? ToolsSeam
            ?: return encodeReason("unimplemented", "tools seam not available")
        return when (method) {
            "list" -> runBlocking {
                val defs = seam.definitions().map { it.name }
                buildJsonObject {
                    put("ok", true)
                    put("tools", defs.joinToString(","))
                }.toString()
            }

            "execute" -> runBlocking {
                val args = json.parseToJsonElement(argsJson).let { it as? JsonObject ?: buildJsonObject { } }
                val toolName = runCatching { args["name"]?.jsonPrimitive?.content }.getOrNull()
                if (toolName.isNullOrBlank()) {
                    encode(false, "missing tool name")
                } else {
                    val toolArgs = args["args"] as? JsonObject ?: buildJsonObject { }
                    val tool = seam.get(toolName)
                    if (tool == null) {
                        encode(false, "tool not found: $toolName")
                    } else {
                        val result: JsonElement = tool.execute(toolArgs)
                        buildJsonObject {
                            put("ok", true)
                            put("data", result)
                        }.toString()
                    }
                }
            }

            else -> encode(false, "unknown tools method: $method")
        }
    }

    /**
     * `sessions` 缝：插件面板读写宿主会话。
     *
     * 路由表：
     * - sessions.list {assistantId?, limit?} → 最近会话列表（id/title/updatedAt）
     * - sessions.get {id} → 会话消息摘要（role + 文本，工具调用折叠）
     * - sessions.send {id, text, answer?} → 向会话发送消息（默认触发 AI 回复）
     */
    private fun handleSessions(method: String, argsJson: String): String {
        val repo = resolve(conversationRepo, "conversation repo") ?: return encodeReason("unimplemented", "sessions seam not available")
        val args = json.parseToJsonElement(argsJson).let { it as? JsonObject ?: buildJsonObject { } }
        return runBlocking {
            when (method) {
                "list" -> {
                    val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 50) ?: 10
                    val assistantId = args["assistantId"]?.jsonPrimitive?.content
                    val conversations = repo.getRecentConversations(
                        assistantId = resolveAssistantId(assistantId),
                        limit = limit,
                    )
                    buildJsonObject {
                        put("ok", true)
                        put(
                            "conversations",
                            buildJsonArray {
                                conversations.forEach { c ->
                                    add(
                                        buildJsonObject {
                                            put("id", c.id.toString())
                                            put("title", c.title)
                                            put("assistantId", c.assistantId.toString())
                                            put("messageCount", c.currentMessages.size)
                                        }
                                    )
                                }
                            },
                        )
                    }.toString()
                }

                "get" -> {
                    val id = args["id"]?.jsonPrimitive?.content
                    if (id.isNullOrBlank()) {
                        encode(false, "missing conversation id")
                    } else {
                        val conversation = runCatching { repo.getConversationById(Uuid.parse(id)) }
                            .getOrNull()
                        if (conversation == null) {
                            encode(false, "conversation not found: $id")
                        } else {
                            buildJsonObject {
                                put("ok", true)
                                put("id", conversation.id.toString())
                                put("title", conversation.title)
                                put(
                                    "messages",
                                    buildJsonArray {
                                        conversation.currentMessages.forEach { msg ->
                                            add(
                                                buildJsonObject {
                                                    put("role", msg.role.name)
                                                    put("text", msg.toText().take(2000))
                                                }
                                            )
                                        }
                                    },
                                )
                            }.toString()
                        }
                    }
                }

                "send" -> {
                    val service = resolve(chatService, "chat service")
                        ?: return@runBlocking encodeReason("unimplemented", "chat service not available")
                    val id = args["id"]?.jsonPrimitive?.content
                    val text = args["text"]?.jsonPrimitive?.content
                    if (id.isNullOrBlank() || text.isNullOrBlank()) {
                        encode(false, "missing id or text")
                    } else {
                        val conversationId = runCatching { Uuid.parse(id) }.getOrNull()
                        if (conversationId == null) {
                            encode(false, "invalid conversation id: $id")
                        } else if (!repo.existsConversationById(conversationId)) {
                            encode(false, "conversation not found: $id")
                        } else {
                            val answer = args["answer"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                            service.sendMessage(
                                conversationId = conversationId,
                                content = listOf(UIMessagePart.Text(text)),
                                answer = answer,
                            )
                            buildJsonObject {
                                put("ok", true)
                                put("message", "sent")
                                put("answer", answer)
                            }.toString()
                        }
                    }
                }

                else -> encode(false, "unknown sessions method: $method")
            }
        }
    }

    /**
     * `events` 缝：宿主事件双通道（R3.2）。
     *
     * 路由表：
     * - events.poll {since, limit?} → seq 大于 since 的宿主事件数组
     *   （chat.generationUpdate / chat.generationEnded，含 conversationId）；
     *   环形缓冲拉取，保留为断线恢复通道
     * - events.subscribe {topics: ["chat."]} → 注册推送订阅，事件到达经
     *   resultDispatcher 主动推 `CordisBridge.onEvent(type, json)`（json 含 seq/type/payload）；
     *   同插件重复订阅替换旧订阅
     * - events.unsubscribe {} → 注销订阅
     */
    private fun handleEvents(method: String, argsJson: String): String {
        val bus = eventBus ?: return encodeReason("unimplemented", "events seam not available")
        val args = json.parseToJsonElement(argsJson).let { it as? JsonObject ?: buildJsonObject { } }
        return when (method) {
            "poll" -> runBlocking {
                val since = args["since"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                val events = bus.poll(since, limit)
                buildJsonObject {
                    put("ok", true)
                    put(
                        "events",
                        buildJsonArray {
                            events.forEach { e ->
                                add(
                                    buildJsonObject {
                                        put("seq", e.seq)
                                        put("type", e.type)
                                        put("payload", e.payload)
                                    }
                                )
                            }
                        },
                    )
                }.toString()
            }

            "subscribe" -> {
                val topics = (args["topics"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?.toSet()
                    ?: emptySet()
                // 替换式去重：bus 侧同 pluginId 仅保留最新订阅，旧句柄自动失效
                synchronized(this) { eventSubscription?.let(bus::unsubscribe) }
                val sub = bus.subscribe(pluginId, topics) { e -> pushEventToJs(e) }
                synchronized(this) { eventSubscription = sub }
                buildJsonObject {
                    put("ok", true)
                    put("message", "subscribed")
                    put("topics", buildJsonArray { topics.forEach { add(JsonPrimitive(it)) } })
                }.toString()
            }

            "unsubscribe" -> {
                synchronized(this) { eventSubscription?.let(bus::unsubscribe) }
                eventSubscription = null
                buildJsonObject { put("ok", true); put("message", "unsubscribed") }.toString()
            }

            else -> encode(false, "unknown events method: $method")
        }
    }

    /** R3.2 事件推送：经 resultDispatcher 主动推 `CordisBridge.onEvent(type, json)`。 */
    private fun pushEventToJs(e: CordisHostEventBus.CordisEvent) {
        val envelope = buildJsonObject {
            put("seq", e.seq)
            put("type", e.type)
            put("payload", e.payload)
        }
        val jsExpr = "window.CordisBridge.onEvent(" +
            json.encodeToString(String.serializer(), e.type) + ", " +
            json.encodeToString(String.serializer(), envelope.toString()) + ")"
        runCatching { resultDispatcher?.invoke(jsExpr) }
            .onFailure { android.util.Log.w("CordisJsBridge", "push event failed: type=${e.type}", it) }
    }

    /** 页面生命周期收口：解绑事件订阅（WebViewPage DisposableEffect onDispose 调用）。 */
    fun release() {
        synchronized(this) {
            eventSubscription?.let { sub ->
                eventBus?.unsubscribe(sub)
            }
            eventSubscription = null
        }
    }

    /** 解析 assistantId：缺省取当前助手。 */
    private suspend fun resolveAssistantId(raw: String?): Uuid {
        if (raw != null) {
            runCatching { return Uuid.parse(raw) }
        }
        val settings = settingsStore?.settingsFlow?.first()
            ?: return Uuid.random()
        return settings.getCurrentAssistant().id
    }

    /** 惰性依赖解析：提供者缺席或解析抛异常都返回 null（调用方转为结构化错误）。 */
    private fun <T> resolve(provider: (() -> T)?, what: String): T? {
        if (provider == null) return null
        return runCatching { provider() }.getOrElse {
            android.util.Log.w("CordisJsBridge", "lazy resolve failed: $what", it)
            null
        }
    }

    private fun encode(ok: Boolean, message: String): String {
        return buildJsonObject {
            put("ok", ok)
            put("message", message)
        }.toString()
    }

    /** R2.4 结构化未实现标记：reason 供 JS 侧与安装期预检区分错误类型。 */
    private fun encodeReason(reason: String, message: String): String {
        return buildJsonObject {
            put("ok", false)
            put("reason", reason)
            put("message", message)
        }.toString()
    }
}