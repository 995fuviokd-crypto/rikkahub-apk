package me.rerere.rikkahub.data.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.cordis.CordisEvent
import me.rerere.rikkahub.data.cordis.CordisKernel
import me.rerere.rikkahub.data.cordis.CordisPlugin
import me.rerere.rikkahub.data.cordis.LlmSeam
import me.rerere.rikkahub.data.cordis.ToolsSeam

/**
 * Cordis 插件桥接：把 [PluginDeclaration] 转换为 [CordisPlugin] 并注册进内核。
 *
 * 同时追踪已注册面板插件，供 UI 层创建 [CordisJsBridge] 实例。
 */
class CordisPluginBridge(
    val kernel: CordisKernel,
    private var jsExec: (suspend (String, String, JsonObject) -> JsonObject)? = null,
    private val agentHost: (() -> me.rerere.rikkahub.data.agent.AgentHost)? = null,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore? = null,
    private val conversationRepo: (() -> me.rerere.rikkahub.data.repository.ConversationRepository)? = null,
    private val chatService: (() -> me.rerere.rikkahub.service.ChatService)? = null,
    private val eventBus: CordisHostEventBus? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val panelPlugins = mutableMapOf<String, PluginDeclaration>()
    private val pluginCapabilities = mutableMapOf<String, Set<String>>()
    private val declarations = mutableMapOf<String, PluginDeclaration>()

    /** 运行时协调者注入 JS 执行器（缺省为 null 时 JS 插件按声明注册，工具经事件桥转发）。 */
    fun setJsExecutor(executor: suspend (String, String, JsonObject) -> JsonObject) {
        jsExec = executor
    }

    /** R3.3：suspend 化透传（kernel.register 已 suspend，apply 在调用方协程执行）。 */
    suspend fun load(declaration: PluginDeclaration) {
        declarations[declaration.id] = declaration
        pluginCapabilities[declaration.id] = declaration.capabilities.toSet()
        if (declaration.kind == PluginDeclarationKind.PANEL) {
            panelPlugins[declaration.id] = declaration
        }
        val plugin = CordisPlugin(
            id = declaration.id,
            inject = declaration.dependencies,
            capabilities = declaration.capabilities,
            apply = onApply(declaration),
        )
        kernel.register(plugin)
    }

    /** 卸载插件：从内核移除并清理追踪表（幂等）。 */
    suspend fun unload(pluginId: String) {
        panelPlugins.remove(pluginId)
        pluginCapabilities.remove(pluginId)
        declarations.remove(pluginId)
        if (kernel.pluginsState.value.contains(pluginId)) {
            runCatching { kernel.unregister(pluginId) }
        }
    }

    /** 插件是否已加载进内核。 */
    fun isLoaded(pluginId: String): Boolean = kernel.pluginsState.value.contains(pluginId)

    /** 已加载插件清单（内核注册序）。 */
    fun loadedPlugins(): List<String> = kernel.pluginsState.value.toList()

    /** 最近一次加载的声明（用于协调者 diff）。 */
    fun declaration(pluginId: String): PluginDeclaration? = declarations[pluginId]

    fun isPanelPlugin(pluginId: String): Boolean = pluginId in panelPlugins

    fun createJsBridge(
        pluginId: String,
        asyncScope: kotlinx.coroutines.CoroutineScope? = null,
        resultDispatcher: ((js: String) -> Unit)? = null,
    ): CordisJsBridge? {
        val caps = pluginCapabilities[pluginId] ?: return null
        return CordisJsBridge(
            pluginId = pluginId,
            kernel = kernel,
            capabilities = caps,
            agentHost = agentHost,
            settingsStore = settingsStore,
            conversationRepo = conversationRepo,
            chatService = chatService,
            eventBus = eventBus,
            asyncScope = asyncScope,
            resultDispatcher = resultDispatcher,
        )
    }

    private fun onApply(declaration: PluginDeclaration): suspend me.rerere.rikkahub.data.cordis.CordisContext.() -> Unit = {
        when (declaration.kind) {
            PluginDeclarationKind.KOTLIN -> {
            }

            PluginDeclarationKind.JS -> {
                val jsRunner = jsExec
                    ?: error("JS executor not configured for plugin '${declaration.id}'")
                registerJsBridge(declaration, jsRunner)
            }

            PluginDeclarationKind.PANEL -> {
                set("panel:${declaration.id}", declaration)
            }
        }
    }

    private suspend fun me.rerere.rikkahub.data.cordis.CordisContext.registerJsBridge(
        declaration: PluginDeclaration,
        jsRunner: suspend (String, String, JsonObject) -> JsonObject,
    ) {
        on("__scriptToolsCall:${declaration.id}") { event ->
            val payload = event.payload
            val toolName = payload["tool"]?.let {
                try {
                    json.parseToJsonElement(it.toString()).jsonPrimitive.content
                } catch (_: Exception) {
                    null
                }
            } ?: return@on null
            val args = payload["args"] ?: return@on null
            when (toolName) {
                "llm" -> {
                    val seam = seam("llm") as? LlmSeam
                    if (seam != null) {
                        val result = seam.infer(args as JsonObject, emptyList())
                        buildJsonObject {
                            put("output", result.output.joinToString("") { it.toText() })
                        }
                    } else null
                }

                "toolRegistry" -> {
                    val seam = seam("tools") as? ToolsSeam
                    if (seam != null) {
                        val names = seam.definitions().joinToString(",") { it.name }
                        buildJsonObject { put("tools", names) }
                    } else null
                }

                else -> {
                    jsRunner(declaration.id, toolName, args as JsonObject)
                }
            }
        }
    }
}