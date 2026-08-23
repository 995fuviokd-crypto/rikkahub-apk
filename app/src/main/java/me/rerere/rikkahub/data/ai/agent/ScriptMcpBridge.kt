package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.script.ScriptRuntime
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Host-side MCP bridge for script market plugins.
 *
 * scripts / ToolPkg tools run inside the app process with QuickJS and the local
 * `Tools.*` runtime (Files/Net/System/Chat …). ACP platform agents are separate CLI
 * sub-processes inside the workspace PRoot container and therefore cannot invoke these
 * tools directly. PRoot does not isolate the network namespace, so the agent can reach
 * `127.0.0.1` on the host; this bridge starts a local HTTP MCP server (Streamable HTTP)
 * exposing the enabled script plugins as the single `run_script_tool` tool and forwards
 * calls back into [ScriptRuntime].
 *
 * Lifecycle is managed by [AcpRuntime] / DI; the server binds an ephemeral port and is
 * stopped via [stop].
 */
class ScriptMcpBridge(
    private val pluginManager: PluginManager,
    private val scriptRuntime: ScriptRuntime,
    private val settingsStore: SettingsStore,
) {
    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var baseUrl: String? = null

    private val json: Json = JsonInstant

    /** Starts the bridge (idempotent) and returns the MCP endpoint URL, e.g. `http://127.0.0.1:49152/mcp`. */
    suspend fun ensureStarted(): String {
        baseUrl?.let { return it }
        return withContext(Dispatchers.IO) {
            val srv = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                routing {
                    post("/mcp") { handleMcp(call) }
                }
            }
            srv.start(wait = false)
            val port = srv.engine.resolvedConnectors().first().port
            val url = "http://127.0.0.1:$port/mcp"
            server = srv
            baseUrl = url
            Log.i(TAG, "脚本 MCP bridge started at $url")
            url
        }
    }

    /** Stops the bridge and forgets the bound URL. */
    fun stop() {
        val srv = server ?: return
        server = null
        baseUrl = null
        runCatching { srv.stop(1000, 2000) }
        Log.i(TAG, "脚本 MCP bridge stopped")
    }

    private suspend fun handleMcp(call: ApplicationCall) {
        val body = runCatching { call.receiveText() }.getOrDefault("")
        val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (request == null) {
            return call.respondText("{}", ContentType.Application.Json, HttpStatusCode.OK)
        }
        val id = request["id"]
        val method = (request["method"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (id == null) {
            // JSON-RPC notification (e.g. notifications/initialized): no reply expected.
            return call.respondText("", ContentType.Application.Json, HttpStatusCode.Accepted)
        }
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", handleInitialize(request))
                "tools/list" -> put("result", handleToolsList())
                "tools/call" -> {
                    val (result, isError) = handleToolsCall(request)
                    if (isError) {
                        put("result", buildJsonObject {
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", (result as? JsonObject)?.get("text")?.jsonPrimitive?.content ?: "tool error")
                                })
                            })
                            put("isError", true)
                        })
                    } else {
                        put("result", result)
                    }
                }
                "ping" -> put("result", buildJsonObject { })
                else -> put("error", buildJsonObject {
                    put("code", -32601)
                    put("message", "Method not found: $method")
                })
            }
        }
        call.respondText(response.toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }

    private fun handleInitialize(request: JsonObject): JsonObject = buildJsonObject {
        val clientVersion = (request["params"] as? JsonObject)?.get("protocolVersion")?.jsonPrimitive?.content
        put("protocolVersion", clientVersion ?: DEFAULT_PROTOCOL_VERSION)
        put(
            "capabilities",
            buildJsonObject {
                put("tools", buildJsonObject { })
            },
        )
        put(
            "serverInfo",
            buildJsonObject {
                put("name", "rikkahub-script")
                put("version", "1.0.0")
            },
        )
    }

    /** Lists the enabled script plugins and their tools as a single `run_script_tool` MCP tool. */
    private fun handleToolsList(): JsonObject {
        val enabled = settingsStore.settingsFlow.value.enabledPlugins
        val entries = enabled.sorted().mapNotNull { id ->
            val info = pluginManager.loadInfo(id) ?: return@mapNotNull null
            val toolNames = scriptRuntime.listToolNames(pluginManager.getPluginDir(id))
            if (toolNames.isEmpty()) return@mapNotNull null
            id to (info.name to toolNames)
        }
        return buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", "run_script_tool")
                            put("description", buildToolDescription(entries))
                            put(
                                "inputSchema",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("plugin_id", buildJsonObject {
                                                put("type", "string")
                                                put("description", "The plugin id (from the tool description)")
                                            })
                                            put("tool", buildJsonObject {
                                                put("type", "string")
                                                put("description", "The tool name exported by the script")
                                            })
                                            put("args", buildJsonObject {
                                                put("type", "object")
                                                put("description", "Arguments for the tool as a JSON object")
                                            })
                                        },
                                    )
                                    put("required", buildJsonArray {
                                        add(JsonPrimitive("plugin_id"))
                                        add(JsonPrimitive("tool"))
                                    })
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    private fun handleToolsCall(request: JsonObject): Pair<JsonElement, Boolean> {
        val params = request["params"] as? JsonObject ?: return buildJsonObject {
            put("text", "missing params")
        } to true
        val name = params["name"]?.jsonPrimitive?.content
        if (name != "run_script_tool") {
            return buildJsonObject { put("text", "unknown tool: $name") } to true
        }
        val arguments = params["arguments"] as? JsonObject ?: buildJsonObject { }
        val pluginId = arguments["plugin_id"]?.jsonPrimitive?.content
        val tool = arguments["tool"]?.jsonPrimitive?.content
        if (pluginId.isNullOrBlank() || tool.isNullOrBlank()) {
            return buildJsonObject { put("text", "plugin_id and tool are required") } to true
        }
        val argsJson = arguments["args"]?.toString() ?: "{}"
        val dir = pluginManager.getPluginDir(pluginId)
        if (!dir.isDirectory) {
            return buildJsonObject { put("text", "插件 $pluginId 未安装") } to true
        }
        val result = scriptRuntime.runTool(dir, pluginId, tool, argsJson)
        val text = if (result.ok) {
            "工具 `$tool` 执行成功" + (result.data?.let { "\n返回：$it" } ?: "")
        } else {
            "工具 `$tool` 执行失败：${result.message}"
        }
        return buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                },
            )
            put("isError", !result.ok)
        } to false
    }

    private fun buildToolDescription(entries: List<Pair<String, Pair<String, List<String>>>>): String = buildString {
        append("Invoke a local tool provided by an script market script or ToolPkg plugin. ")
        if (entries.isEmpty()) {
            append("No script plugins are currently enabled.")
            return@buildString
        }
        append("Enabled plugins and their tools:\n")
        entries.forEach { (id, pair) ->
            append("- 插件「").append(pair.first).append("」（plugin_id=").append(id).append("）：\n")
            pair.second.forEach { tool ->
                append("  - `").append(tool).append("`\n")
            }
        }
        append("Call with plugin_id, tool and args (JSON object). Tools.* runtime maps to RikkaHub local capabilities: file read/write, HTTP, notifications, calc, chat access.")
    }

    companion object {
        private const val TAG = "ScriptMcpBridge"
        private const val DEFAULT_PROTOCOL_VERSION = "2024-11-05"
    }
}
