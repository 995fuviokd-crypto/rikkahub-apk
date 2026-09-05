package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.script.ScriptRuntime
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.plugin.PluginPipelineSnapshot

/**
 * 脚本插件工具直出（design.md D2.4 / R4.4 完整版）：
 * 每个启用脚本插件的每个工具以 `pluginId.toolName` 独立 Tool 直出工具列表，
 * 聊天工具调用块直接显示插件名与工具名；执行统一路由 QuickJS 沙箱。
 *
 * 同时保留旧三段式 `run_script_tool` 兼容解析（旧会话/旧提示词仍可调用）。
 * 工具清单来自 [PluginManager.pipelineSnapshot] 冷数据快照（与插件中心/Hook 一致视图）。
 */
internal fun buildScriptTools(
    pluginManager: PluginManager,
    runtime: ScriptRuntime,
    settingsStore: SettingsStore,
): List<Tool> {
    val snapshot = runCatching { pluginManager.pipelineSnapshot() }.getOrNull()
        ?: PluginPipelineSnapshot(emptySet(), emptyMap(), emptyList(), emptyList())
    val direct = snapshot.tools.flatMap { entry ->
        entry.toolNames.map { toolName ->
            buildDirectScriptTool(pluginManager, runtime, settingsStore, entry.pluginId, entry.pluginName, toolName)
        }
    }
    // 兼容通道：无任何直出工具时旧三段式也不必出现（清单为空即无能力）
    return if (direct.isEmpty()) emptyList() else direct + buildLegacyScriptTool(pluginManager, runtime, settingsStore)
}

/** 直出单工具：name = pluginId.toolName，参数宽松透传 JSON 对象 */
private fun buildDirectScriptTool(
    pluginManager: PluginManager,
    runtime: ScriptRuntime,
    settingsStore: SettingsStore,
    pluginId: String,
    pluginName: String,
    toolName: String,
): Tool = Tool(
    name = "$pluginId.$toolName",
    description = "[插件 $pluginName] 调用脚本工具 `$toolName`。参数为 JSON 对象，直接透传给脚本函数。" +
        "Tools.* 运行时已真实映射（文件/HTTP/系统/会话能力）。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("args", buildJsonObject {
                    put("type", "object")
                    put("description", "Arguments passed to the script tool as a JSON object")
                })
            },
            required = listOf("args"),
        )
    },
    systemPrompt = { _, _ ->
        // 直出形态下工具即工具，无需额外清单提示；仅说明直出命名规则
        "脚本插件工具已直接出现在工具列表（命名 `pluginId.toolName`），参数经 args 对象透传。"
    },
    execute = { input ->
        val argsJson = (input.jsonObject)["args"]?.toString() ?: "{}"
        executeScriptTool(pluginManager, runtime, settingsStore, pluginId, pluginName, toolName, argsJson)
    },
)

/** 旧三段式兼容通道：run_script_tool(plugin_id, tool, args)；执行与直出同一路由 */
private fun buildLegacyScriptTool(
    pluginManager: PluginManager,
    runtime: ScriptRuntime,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "run_script_tool",
    description = "兼容调用通道：Invoke a local tool provided by an installed script plugin. " +
        "Prefer the direct `pluginId.toolName` tools listed in the tools array. " +
        "Example: {\"plugin_id\": \"community-chat-filter-xxxx\", \"tool\": \"list_chats\", \"args\": {}}",
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
    systemPrompt = { _, _ -> "" },
    execute = { input ->
        val json = input.jsonObject
        val pluginId = json["plugin_id"]?.jsonPrimitive?.content
            ?: error("plugin_id is required")
        val tool = json["tool"]?.jsonPrimitive?.content
            ?: error("tool is required")
        val argsJson = json["args"]?.jsonObject?.toString() ?: "{}"
        val pluginName = pluginManager.loadInfo(pluginId)?.name ?: pluginId
        executeScriptTool(pluginManager, runtime, settingsStore, pluginId, pluginName, tool, argsJson)
    },
)

/** 统一执行路由：启用校验 → QuickJS 沙箱 runTool → 结构化文本结果 */
private suspend fun executeScriptTool(
    pluginManager: PluginManager,
    runtime: ScriptRuntime,
    settingsStore: SettingsStore,
    pluginId: String,
    pluginName: String,
    toolName: String,
    argsJson: String,
): List<UIMessagePart> {
    // 与工具清单一致：仅已启用插件可执行，防止绕过用户禁用决策
    if (pluginId !in settingsStore.settingsFlow.value.enabledPlugins) {
        return listOf(UIMessagePart.Text("插件 $pluginName（$pluginId）未启用，请在「扩展-插件」页启用后使用"))
    }
    val dir = pluginManager.getPluginDir(pluginId)
    if (!dir.isDirectory) {
        return listOf(UIMessagePart.Text("插件 $pluginName（$pluginId）未安装"))
    }
    val result = runtime.runTool(dir, pluginId, toolName, argsJson)
    val text = if (result.ok) {
        "工具 `$toolName` 执行成功" + (result.data?.let { "\n返回：$it" } ?: "")
    } else {
        "工具 `$toolName` 执行失败：${result.message}"
    }
    return listOf(UIMessagePart.Text(text))
}
