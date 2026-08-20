package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.operit.OperitScriptRuntime
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginManager

/**
 * 调用 Operit 市场脚本 / ToolPkg 提供的本地工具。插件安装后，其导出的工具函数
 * 由 OperitScriptRuntime 用 QuickJS 在本地真实执行，Tools.* 运行时映射到
 * RikkaHub 本地能力。systemPrompt 动态列出当前已启用插件可用工具。
 */
internal fun buildOperitTool(
    pluginManager: PluginManager,
    runtime: OperitScriptRuntime,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "run_operit_tool",
    description = """
        Invoke a local tool provided by an Operit market script or ToolPkg plugin.
        Use this tool to call functions exported by Operit scripts (chat filtering,
        media parsing, notifications, file helpers, etc.) that are installed and
        enabled in the Extensions page. The available plugins and tools are listed
        in the system prompt. Pass args as a JSON object matching the tool parameters.
        Example: {"plugin_id": "operit-chat-filter-xxxx", "tool": "list_chats", "args": {}}
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        buildString {
            val enabled = settingsStore.settingsFlow.value.enabledPlugins
            val entries = enabled.sorted().mapNotNull { id ->
                val info = pluginManager.loadInfo(id) ?: return@mapNotNull null
                val toolNames = runtime.listToolNames(pluginManager.getPluginDir(id))
                if (toolNames.isEmpty()) return@mapNotNull null
                id to (info.name to toolNames)
            }
            if (entries.isEmpty()) return@buildString
            appendLine("**Operit 本地工具**")
            appendLine("已启用以下 Operit 脚本/ToolPkg 插件，可使用 `run_operit_tool` 调用其工具（Tools.* 中文件/通知能力已在本地映射，对话/工作流等 Operit 专有 API 会返回受限提示）：")
            entries.forEach { (id, pair) ->
                appendLine("- 插件「${pair.first}」（plugin_id=$id）：")
                pair.second.forEach { tool ->
                    appendLine("  - `$tool`")
                }
            }
            appendLine("调用参数：plugin_id（上述 id）、tool（工具名）、args（JSON 对象）")
        }
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("plugin_id", buildJsonObject {
                    put("type", "string")
                    put("description", "The Operit plugin id (from the system prompt list)")
                })
                put("tool", buildJsonObject {
                    put("type", "string")
                    put("description", "The tool name exported by the Operit script")
                })
                put("args", buildJsonObject {
                    put("type", "object")
                    put("description", "Arguments for the tool as a JSON object")
                })
            },
            required = listOf("plugin_id", "tool")
        )
    },
    execute = { it ->
        val json = it.jsonObject
        val pluginId = json["plugin_id"]?.jsonPrimitive?.content
            ?: error("plugin_id is required")
        val tool = json["tool"]?.jsonPrimitive?.content
            ?: error("tool is required")
        val argsObj = json["args"]?.jsonObject
        val argsJson = argsObj?.toString() ?: "{}"
        val dir = pluginManager.getPluginDir(pluginId)
        if (!dir.isDirectory) {
            return@Tool listOf(UIMessagePart.Text("插件 $pluginId 未安装"))
        }
        val result = runtime.runTool(dir, pluginId, tool, argsJson)
        val text = if (result.ok) {
            "工具 `$tool` 执行成功" + (result.data?.let { "\n返回：$it" } ?: "")
        } else {
            "工具 `$tool` 执行失败：${result.message}"
        }
        listOf(UIMessagePart.Text(text))
    },
)
