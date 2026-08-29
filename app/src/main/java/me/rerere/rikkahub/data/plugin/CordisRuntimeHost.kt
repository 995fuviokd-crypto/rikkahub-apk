package me.rerere.rikkahub.data.plugin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.cordis.CordisKernel
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.script.ScriptRuntime
import java.io.File

/**
 * DSH/脚本插件运行时协调者：把已安装且启用的、具备可执行能力的插件
 * （面板 PANEL、脚本 JS）加载进 [CordisKernel]，并在启用/禁用/卸载时热插拔。
 *
 * - 面板插件（web/plugin.client.js）→ PANEL：随 WebView 面板运行，经 CordisJsBridge
 *   访问宿主能力缝（llm/tools/sessions/systemPrompt）。
 * - 脚本插件（script/ 目录含 JS 入口）→ JS：经 default js executor 路由到 ScriptRuntime
 *   的 QuickJS 沙箱执行。
 * - 纯提示词/技能型插件（无 web/plugin.client.js、无脚本目录）仅注入 systemPrompt，
 *   不占用内核注册表。
 *
 * 同步触发器：
 * - 插件目录事件（安装/卸载/包内容变更，见 [PluginManager.directoryEvents]）
 * - enabledPlugins 集合变化（[SettingsStore] settingsFlow）
 */
class CordisRuntimeHost(
    private val pluginManager: PluginManager,
    private val bridge: CordisPluginBridge,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    private val kernel: CordisKernel,
) {
    companion object {
        private const val TAG = "CordisRuntimeHost"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** 加载进内核的插件可访问的宿主能力缝白名单（R7.4 seam 校验需要声明） */
        val HOST_CAPABILITIES = setOf("llm", "tools", "sessions", "systemPrompt", "events")
    }

    /** 当前已加载进内核的插件 id 集合（诊断/UI 用） */
    private val _loadedIds = MutableStateFlow<Set<String>>(emptySet())
    val loadedIds: StateFlow<Set<String>> = _loadedIds.asStateFlow()

    private val syncMutex = Mutex()
    @Volatile
    private var started = false

    /** 启动协调者：初始同步 + 订阅插件目录变化（防抖合并突发事件）。 */
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.Default) {
            try {
                sync()
            } catch (t: Throwable) {
                Log.w(TAG, "initial sync failed", t)
            }
        }
        scope.launch(Dispatchers.Default) {
            combine(
                pluginManager.directoryEvents,
                settingsStore.settingsFlow,
            ) { _, settings -> settings.enabledPlugins }
                .debounce(300)
                .flowOn(Dispatchers.Default)
                .collect {
                    try {
                        sync()
                    } catch (t: Throwable) {
                        Log.w(TAG, "sync failed on change", t)
                    }
                }
        }
    }

    /** 关闭协调者：从内核卸载已加载插件并停止监听。 */
    suspend fun shutdown() {
        if (!started) return
        started = false
        syncMutex.withLock {
            val current = kernel.pluginsState.value.toList()
            current.forEach { id ->
                try {
                    bridge.unload(id)
                } catch (t: Throwable) {
                    Log.w(TAG, "unload failed: $id", t)
                }
            }
            _loadedIds.value = emptySet()
        }
    }

    /**
     * 全量对账：期望集合 = enabledPlugins ∩ 可加载插件；与内核现状 diff 后增量加载/卸载。
     * 幂等，可任意时刻调用。
     */
    suspend fun sync() = syncMutex.withLock {
        withContext(Dispatchers.Default) {
            val enabled = settingsStore.settingsFlow.value.enabledPlugins
            val installed = pluginManager.listPlugins()
            val expected = installed
                .filter { it.id in enabled && it.info != null }
                .mapNotNull { buildDeclaration(it) }
            val expectedIds = expected.mapTo(mutableSetOf()) { it.id }

            // 卸载消失的
            val current = kernel.pluginsState.value.toSet()
            (current - expectedIds).forEach { id ->
                try {
                    bridge.unload(id)
                } catch (t: Throwable) {
                    Log.w(TAG, "unload failed: $id", t)
                }
            }

            // 加载新增
            expected.forEach { declaration ->
                if (!kernel.pluginsState.value.contains(declaration.id)) {
                    try {
                        bridge.load(declaration)
                    } catch (t: Throwable) {
                        Log.w(TAG, "load failed: ${declaration.id}", t)
                    }
                }
            }

            _loadedIds.value = kernel.pluginsState.value.toSet()
        }
    }

    /** 把已安装插件映射为内核声明；无可执行能力（纯提示词型）返回 null。 */
    private fun buildDeclaration(plugin: InstalledPlugin): PluginDeclaration? {
        val info = plugin.info ?: return null
        val dir = pluginManager.getPluginDir(plugin.id)
        val panelClient = File(dir, "web/plugin.client.js")
        val scriptDir = ScriptRuntime.scriptDir(dir)
        val hasScript = scriptDir.isDirectory && scriptDir.listFiles().orEmpty().any { it.extension == "js" }

        val capabilities = HOST_CAPABILITIES + info.tags.orEmpty().filter { it.startsWith("cap:") }

        return when {
            panelClient.isFile -> PluginDeclaration(
                id = info.id,
                name = info.name,
                version = info.version,
                description = info.description,
                author = info.author,
                kind = PluginDeclarationKind.PANEL,
                capabilities = capabilities.toList(),
                hooks = info.hooks.map { PluginHookDeclaration(it.name, it.description, it.timeoutMs) },
                systemPrompt = info.systemPrompt,
                config = info.configSchema?.let { schema ->
                    Json.encodeToJsonElement(me.rerere.rikkahub.data.plugin.PluginConfigSchema.serializer(), schema).jsonObject
                },
            )

            hasScript -> PluginDeclaration(
                id = info.id,
                name = info.name,
                version = info.version,
                description = info.description,
                author = info.author,
                kind = PluginDeclarationKind.JS,
                capabilities = capabilities.toList(),
                hooks = info.hooks.map { PluginHookDeclaration(it.name, it.description, it.timeoutMs) },
                systemPrompt = info.systemPrompt,
                config = info.configSchema?.let { schema ->
                    Json.encodeToJsonElement(me.rerere.rikkahub.data.plugin.PluginConfigSchema.serializer(), schema).jsonObject
                },
            )

            else -> null
        }
    }
}