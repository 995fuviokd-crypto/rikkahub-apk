package me.rerere.rikkahub.data.plugin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.cordis.ToolSeamDefinition
import me.rerere.rikkahub.data.cordis.ToolsSeam
import me.rerere.rikkahub.data.script.ScriptRuntime
import me.rerere.rikkahub.data.script.ScriptToolDef
import me.rerere.rikkahub.data.script.ScriptToolManifest
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.io.File

/**
 * `tools` 缝生产者（R2.2）：把已启用的 JS 脚本插件工具注册进 [ToolsSeam] 注册表。
 *
 * - 工具以 `pluginId.toolName` 命名直出（R4.4/任务 9 元工具拆出的数据基础）
 * - 执行经 [ScriptRuntime.runTool] 路由进 V8 沙箱
 * - 注册表变更由 HostToolsSeam 事件驱动派发 `tools/change`
 * - 同步触发器与 CordisRuntimeHost 一致：插件目录事件 + enabledPlugins 变化（防抖合并）
 */
class ScriptToolsSeamProducer(
    private val pluginManager: PluginManager,
    private val scriptRuntime: ScriptRuntime,
    private val settingsStore: SettingsStore,
    private val toolsSeam: ToolsSeam,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ScriptToolsSeam"

        /** 缝内工具命名：pluginId.toolName（与任务 9 元工具拆出后的直出形态一致）。 */
        fun seamToolName(pluginId: String, toolName: String): String = "$pluginId.$toolName"
    }

    private val syncMutex = Mutex()
    private val registered = mutableMapOf<String, String>() // seamToolName -> pluginId（诊断/卸载用）

    @Volatile
    private var started = false

    /** 启动生产者：初始全量对账 + 订阅变化（幂等）。 */
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.Default) {
            runCatching { sync() }.onFailure { Log.w(TAG, "initial sync failed", it) }
        }
        scope.launch(Dispatchers.Default) {
            combine(
                pluginManager.directoryEvents,
                settingsStore.settingsFlow,
            ) { _, settings -> settings.enabledPlugins }
                .debounce(300)
                .flowOn(Dispatchers.Default)
                .collect {
                    runCatching { sync() }.onFailure { Log.w(TAG, "sync failed on change", it) }
                }
        }
    }

    /** 全量对账：期望集合 = 启用且有 script 工具的插件工具；diff 后增量注册/注销。幂等。 */
    suspend fun sync() = syncMutex.withLock {
        withContext(Dispatchers.Default) {
            val enabled = settingsStore.settingsFlow.value.enabledPlugins
            val expected = mutableMapOf<String, Pair<String, ScriptToolDef>>() // seamName -> (pluginId, def)
            pluginManager.listPlugins().forEach { plugin ->
                if (plugin.id !in enabled) return@forEach
                val dir = pluginManager.getPluginDir(plugin.id)
                val scriptDir = ScriptRuntime.scriptDir(dir)
                if (!scriptDir.isDirectory) return@forEach
                ScriptToolManifest.toolsFromDirectory(scriptDir).forEach { toolDef ->
                    expected[seamToolName(plugin.id, toolDef.name)] = plugin.id to toolDef
                }
            }

            // 注销消失的
            registered.keys.filter { it !in expected.keys }.forEach { seamName ->
                runCatching { toolsSeam.unregister(seamName) }
                    .onFailure { Log.w(TAG, "unregister failed: $seamName", it) }
                registered.remove(seamName)
            }

            // 注册新增的
            expected.forEach { (seamName, pluginAndDef) ->
                if (seamName in registered) return@forEach
                val (pluginId, toolDef) = pluginAndDef
                val def = ToolSeamDefinition(
                    name = seamName,
                    description = "[plugin:${pluginId}] ${toolDef.description}",
                ) { args ->
                    executePluginTool(pluginId, toolDef.name, args)
                }
                runCatching { toolsSeam.register(def) }
                    .onSuccess { registered[seamName] = pluginId }
                    .onFailure { Log.w(TAG, "register failed: $seamName", it) }
            }
        }
    }

    /** 当前已注册的缝工具名（测试/诊断）。 */
    fun registeredToolNames(): List<String> = registered.keys.toList()

    /** 执行插件工具：路由进 V8 沙箱，结果包装为结构化 JsonElement。 */
    private suspend fun executePluginTool(
        pluginId: String,
        toolName: String,
        args: JsonElement,
    ): JsonElement = withContext(Dispatchers.Default) {
        val pluginDir: File = pluginManager.getPluginDir(pluginId)
        val argsJson = args.toString()
        runCatching {
            scriptRuntime.runTool(pluginDir, pluginId, toolName, argsJson)
        }.getOrElse { t ->
            ScriptRuntime.ToolResult(ok = false, message = t.message ?: "plugin tool execution failed", data = null)
        }.let { result ->
            buildJsonObject {
                put("ok", result.ok)
                put("message", result.message)
                result.data?.let { put("data", it) }
            }
        }
    }
}
