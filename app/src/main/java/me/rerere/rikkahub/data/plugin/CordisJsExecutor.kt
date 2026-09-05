package me.rerere.rikkahub.data.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.script.ScriptRuntime

/**
 * 默认 Cordis JS 执行器：把 [CordisPluginBridge] 收到的 JS 插件工具调用
 * 转发到 [ScriptRuntime] 的 QuickJS 沙箱执行。
 *
 * 桥接签名与 ScriptRuntime.runTool 对齐：插件根目录 + 插件 id + 工具名 + JSON 参数。
 * JS 插件（kind=JS）在其脚本目录（script/）内自由使用 Files/Net/System/Chat 等 Tools API，
 * 与 RikkaHub 手写脚本插件共用同一套运行时沙箱。
 *
 * R3.3：QuickJS 解释执行为 CPU 密集，统一切 [Dispatchers.Default]，
 * 避免占用调用方调度线程（如 Agent 循环所在的 IO 线程）。
 */
class CordisJsExecutor(
    private val pluginManager: PluginManager,
    private val scriptRuntime: ScriptRuntime,
) {
    suspend operator fun invoke(pluginId: String, toolName: String, args: JsonObject): JsonObject =
        withContext(Dispatchers.Default) {
            val pluginDir = pluginManager.getPluginDir(pluginId)
            val result = scriptRuntime.runTool(pluginDir, pluginId, toolName, args.toString())
            buildJsonObject {
                put("ok", result.ok)
                put("message", result.message)
                result.data?.let { put("data", it) }
            }
        }
}