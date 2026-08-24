package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.script.ScriptRuntime
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginManager

/**
 * 调用 社区市场脚本 / ToolPkg 提供的本地工具。插件安装后，其导出的工具函数
 * 由 ScriptRuntime 用 QuickJS 在本地真实执行，Tools.* 运行时映射到
 * RikkaHub 本地能力。systemPrompt 动态列出当前已启用插件可用工具。
 */
internal fun buildScriptTool(
    pluginManager: PluginManager,
    runtime: ScriptRuntime,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "run_script_tool",
    description = """
        Invoke a local tool provided by an script market script or ToolPkg plugin.
        Use this tool to call functions exported by scripts (chat filtering,
        media parsing, notifications, file helpers, etc.) that are installed and
        enabled in the Extensions page. The available plugins and tools are listed
        in the system prompt. Pass args as a JSON object matching the tool parameters.
        Example: {"plugin_id": "community-chat-filter-xxxx", "tool": "list_chats", "args": {}}
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
            appendLine("**本地脚本工具**")
            appendLine("已启用以下 脚本/ToolPkg 插件，可使用 `run_script_tool` 调用其工具。Tools.* 运行时已真实映射：文件读写（Tools.Files，沙箱目录）、HTTP 请求与网页抓取（Tools.Net）、系统能力如 sleep/toast/通知/设备信息（Tools.System）、表达式计算（Tools.calc）、本地会话读写（Tools.Chat，读取/创建/删除/改名 RikkaHub 会话与消息）；UI 自动化、浏览器控制、sendMessage 等依赖界面状态或特权的 社区专有 API 返回受限提示。")
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
                    put("description", "The plugin id (from the system prompt list)")
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
        // 与 systemPrompt 清单一致：仅已启用插件可执行，防止绕过用户禁用决策
        if (pluginId !in settingsStore.settingsFlow.value.enabledPlugins) {
            return@Tool listOf(UIMessagePart.Text("插件 $pluginId 未启用，请在「扩展-插件」页启用后使用"))
        }
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
