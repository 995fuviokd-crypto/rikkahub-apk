package me.rerere.rikkahub.data.ai.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.script.ScriptRuntime
import me.rerere.rikkahub.data.plugin.PluginManager

/**
 * Builds the ACP `session/new` `mcpServers` payload for a platform agent session.
 *
 * Two sources are combined:
 * 1. **script plugins** — when script/ToolPkg plugins are enabled, the host-side
 *    [ScriptMcpBridge] is started and advertised as an HTTP MCP server, so the agent can
 *    call `run_script_tool` inside the container.
 * 2. **User-configured MCP servers** — enabled servers from [SettingsStore] are converted
 *    to the ACP `mcpServers` transport format (stdio / http / sse), so the agent can reach
 *    the same remote servers the chat path uses.
 */
class AcpMcpServersBuilder(
    private val pluginManager: PluginManager,
    private val scriptRuntime: ScriptRuntime,
    private val settingsStore: SettingsStore,
    private val scriptBridge: ScriptMcpBridge,
) {
    /**
     * Builds the ACP `mcpServers` array for a new agent session.
     */
    suspend fun build(): List<JsonElement> {
        val settings = settingsStore.settingsFlow.value
        val servers = mutableListOf<JsonElement>()

        // 1. script/ToolPkg plugins -> host HTTP MCP bridge
        val hasScript = pluginManager.hasScriptPlugins(settings.enabledPlugins)
        if (hasScript) {
            val url = scriptBridge.ensureStarted()
            servers += buildJsonObject {
                put("type", "http")
                put("name", "rikkahub-script")
                put("url", url)
                put(
                    "headers",
                    buildJsonArray { },
                )
            }
        }

        // 2. Enabled user/plugin-configured MCP servers
        //    本地命令型（stdio）需要外部进程环境，Android 端不支持，不传给 agent
        settings.mcpServers
            .filter { it.commonOptions.enable }
            .filter { it !is McpServerConfig.CommandServerConfig }
            .forEach { servers += it.toAcpMcpServer() }

        return servers
    }
}

/** Converts a RikkaHub MCP server config into the ACP `mcpServers` element for its transport. */
internal fun McpServerConfig.toAcpMcpServer(): JsonElement = when (this) {
    is McpServerConfig.SseTransportServer -> buildJsonObject {
        put("type", "sse")
        put("name", commonOptions.name.ifBlank { id.toString() })
        put("url", url)
        put("headers", headersToAcpArray())
    }
    is McpServerConfig.StreamableHTTPServer -> buildJsonObject {
        put("type", "http")
        put("name", commonOptions.name.ifBlank { id.toString() })
        put("url", url)
        put("headers", headersToAcpArray())
    }
    is McpServerConfig.CommandServerConfig -> buildJsonObject {
        put("name", commonOptions.name.ifBlank { id.toString() })
        put("command", command)
        put(
            "args",
            buildJsonArray { args.forEach { add(JsonPrimitive(it)) } },
        )
        put(
            "env",
            buildJsonArray {
                env.forEach { (k, v) ->
                    add(
                        buildJsonObject {
                            put("name", k)
                            put("value", v)
                        },
                    )
                }
            },
        )
    }
}

private fun McpServerConfig.headersToAcpArray() = buildJsonArray {
    commonOptions.headers.forEach { (k, v) ->
        add(
            buildJsonObject {
                put("name", k)
                put("value", v)
            },
        )
    }
}