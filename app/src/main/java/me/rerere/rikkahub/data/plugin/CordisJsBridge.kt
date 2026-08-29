package me.rerere.rikkahub.data.plugin

import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * 面板 JS 侧调用示例：
 * ```
 * const r = JSON.parse(window.CordisBridge.seamCall("llm", "infer", JSON.stringify({...})));
 * const tools = JSON.parse(window.CordisBridge.seamCall("tools", "list", "{}"));
 * ```
 */
class CordisJsBridge(
    private val pluginId: String,
    private val kernel: CordisKernel,
    private val capabilities: Set<String>,
    private val agentHost: me.rerere.rikkahub.data.agent.AgentHost? = null,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore? = null,
    private val conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository? = null,
    private val chatService: me.rerere.rikkahub.service.ChatService? = null,
    private val eventBus: CordisHostEventBus? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
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
                return encode(false, "capability '$seamName' not declared for plugin '$pluginId'")
            }
            when (seamName) {
                "llm" -> handleLlm(method, argsJson)
                "tools" -> handleTools(method, argsJson)
                "agent" -> handleAgent(method, argsJson)
                "sessions" -> handleSessions(method, argsJson)
                "events" -> handleEvents(method, argsJson)
                else -> encode(false, "unknown seam: $seamName")
            }
        }.getOrElse { e ->
            encode(false, e.message ?: "CordisJsBridge error")
        }
    }

    private fun handleAgent(method: String, argsJson: String): String {
        if (method != "run") return encode(false, "unknown agent method: $method")
        val host = agentHost ?: return encode(false, "agent host not available")
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

    private fun handleLlm(method: String, argsJson: String): String {
        val seam = kernel.rootContext.get("llm") as? LlmSeam
            ?: return encode(false, "llm seam not available")
        if (method != "infer") return encode(false, "unknown llm method: $method")
        return runBlocking {
            val args = json.parseToJsonElement(argsJson).let { it as? JsonObject ?: buildJsonObject { } }
            val result = seam.infer(args, emptyList())
            buildJsonObject {
                put("ok", true)
                put("output", result.output.joinToString("") { it.toText() })
                put("model", result.model)
                put("provider", result.provider)
            }.toString()
        }
    }

    private fun handleTools(method: String, argsJson: String): String {
        val seam = kernel.rootContext.get("tools") as? ToolsSeam
            ?: return encode(false, "tools seam not available")
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
        val repo = conversationRepo ?: return encode(false, "sessions seam not available")
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
                    val service = chatService ?: return@runBlocking encode(false, "chat service not available")
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
     * `events` 缝：宿主事件增量拉取。
     *
     * 路由表：
     * - events.poll {since, limit?} → seq 大于 since 的宿主事件数组
     *   （chat.generationUpdate / chat.generationEnded，含 conversationId）
     */
    private fun handleEvents(method: String, argsJson: String): String {
        val bus = eventBus ?: return encode(false, "events seam not available")
        if (method != "poll") return encode(false, "unknown events method: $method")
        return runBlocking {
            val args = json.parseToJsonElement(argsJson).let { it as? JsonObject ?: buildJsonObject { } }
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

    private fun encode(ok: Boolean, message: String): String {
        return buildJsonObject {
            put("ok", ok)
            put("message", message)
        }.toString()
    }
}