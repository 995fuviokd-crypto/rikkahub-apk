package me.rerere.rikkahub.data.cordis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/**
 * Cordis 插件声明：inject 声明依赖，apply 注入 ctx 副作用。
 *
 * 对齐 dsh/Cordis：plugin = Fn{ inject, apply(ctx) }。
 */
class CordisPlugin(
    val id: String,
    val inject: List<String> = emptyList(),
    /** 声明可访问的能力缝白名单（R7.4）；未声明的能力缝访问将拒绝。 */
    val capabilities: List<String> = emptyList(),
    val apply: suspend CordisContext.() -> Unit = {},
)

/**
 * 可逆副作用：插件在 ctx 上注册的能力，内核在卸载时逆序取消。
 */
internal class CordisEffect(
    val name: String,
    val pluginId: String,
    private val disposeBlock: suspend () -> Unit,
) {
    suspend fun dispose() = disposeBlock()
}

/**
 * Cordis 上下文：服务仓库 + 事件监听 + 副作用注册。
 *
 * 对齐 dsh/Cordis ctx 语义：
 * - 服务经 [set]/[get] 存取，支持父链冒泡；插件共享 root ctx，set 全局可见
 * - [on] 注册事件监听（前缀匹配），[emit]/[dispatch] 分发
 * - [effect] 注册可逆副作用，作用域销毁时逆序回收
 * - [child] 派生子作用域（isolate），子作用域服务视图继承父作用域
 */
class CordisContext internal constructor(
    internal val kernel: CordisKernel,
    private val parent: CordisContext? = null,
) {
    private val services = mutableMapOf<String, CordisService>()
    private val effects = mutableListOf<CordisEffect>()
    private val models = mutableListOf<CordisModel>()
    private val commands = mutableListOf<CordisCommand>()
    private val handles = mutableListOf<CordisListenerHandle>()
    private val children = mutableListOf<CordisContext>()

    val eventBus: CordisEventBus get() = kernel.eventBus

    // ---- 服务仓库 ----

    /** 注册服务（owner 为当前插件）。同层重复注册覆盖。 */
    fun set(name: String, value: Any?) {
        services[name] = CordisService(name, value, kernel.currentPluginId)
    }

    /** 取服务：本层无则冒泡父链。 */
    fun get(name: String): Any? = services[name]?.value ?: parent?.get(name)

    /** 取 Action 型服务并调用。 */
    suspend fun call(name: String, payload: JsonObject): JsonObject {
        val service = get(name) ?: error("service not found: $name")
        @Suppress("UNCHECKED_CAST")
        val action = service as? suspend (JsonObject) -> JsonObject
            ?: error("service $name is not a callable action")
        return action(payload)
    }

    /** 是否存在服务（含父链） */
    fun has(name: String): Boolean = services.containsKey(name) || (parent?.has(name) == true)

    // ---- 事件 ----

    /** 注册事件监听，返回可注销句柄。 */
    fun on(pattern: String, handler: suspend (CordisEvent) -> JsonObject?): CordisListenerHandle {
        val handle = kernel.eventBus.on(pattern, kernel.currentPluginId, handler)
        handles += handle
        return handle
    }

    /** 注销事件监听 */
    fun off(handle: CordisListenerHandle) {
        kernel.eventBus.off(handle)
        handles.remove(handle)
    }

    /** Emit 语义广播。 */
    suspend fun emit(event: CordisEvent) {
        kernel.eventBus.emit(event)
    }

    /** 按指定模式分发。 */
    suspend fun dispatch(mode: DispatchMode, event: CordisEvent): List<JsonObject?> =
        kernel.eventBus.dispatch(mode, event)

    // ---- 副作用 ----

    /** 注册可逆副作用，作用域销毁时逆序执行 dispose。 */
    fun effect(name: String, disposeBlock: suspend () -> Unit) {
        effects += CordisEffect(name, kernel.currentPluginId, disposeBlock)
    }

    // ---- 模型 / 命令 ----

    /** 注册模型声明：ctx.model(name, config) */
    fun model(name: String, config: JsonObject) {
        models += CordisModel(name, config, kernel.currentPluginId)
    }

    fun models(): List<CordisModel> = models.toList()

    /** 注册命令：ctx.command(name, body, action)，action 同时注册为可调用服务。 */
    fun command(name: String, body: String = "", action: (suspend (JsonObject) -> JsonObject)? = null) {
        commands += CordisCommand(name, body, kernel.currentPluginId, action)
        if (action != null) set(name, action)
    }

    fun commands(): List<CordisCommand> = commands.toList()

    // ---- 作用域 ----

    /** 派生子作用域（isolate）：继承父服务视图，可覆盖。 */
    fun child(): CordisContext {
        val ctx = kernel.createContext(parent = this)
        children += ctx
        return ctx
    }

    /** 递归销毁子作用域（仅回收本作用域副作用，不影响父服务）。 */
    fun dispose() {
        children.forEach { it.dispose() }
        children.clear()
        effects.asReversed().forEach { effect ->
            runCatching { kotlinx.coroutines.runBlocking { effect.dispose() } }
        }
        effects.clear()
        handles.forEach { kernel.eventBus.off(it) }
        handles.clear()
        services.clear()
        models.clear()
        commands.clear()
        kernel.onContextDisposed(this)
    }

    /** 仅按插件 ID 清理该插件注册的服务/模型/命令/effects/监听（保留本作用域其他内容）。 */
    internal fun disposePlugin(pluginId: String) {
        effects.asReversed()
            .filter { it.pluginId == pluginId }
            .forEach { effect ->
                runCatching { kotlinx.coroutines.runBlocking { effect.dispose() } }
            }
        effects.removeAll { it.pluginId == pluginId }
        services.entries.removeAll { it.value.pluginId == pluginId }
        models.removeAll { it.pluginId == pluginId }
        commands.removeAll { it.pluginId == pluginId }
        handles.toList().forEach { handle ->
            if (kernel.eventBus.listenerOwner(handle) == pluginId) {
                kernel.eventBus.off(handle)
                handles.remove(handle)
            }
        }
    }

    /** 快照服务名集合（含父链），供诊断 */
    fun serviceNames(): Set<String> {
        val local = services.keys.toSet()
        return local + (parent?.serviceNames() ?: emptySet())
    }

    // ---- 能力缝（capability seams）----

    /**
     * 访问能力缝服务。插件必须在 [CordisPlugin.capabilities] 中声明该能力名，
     * 否则抛出 [CordisCapabilityNotDeclaredException]（R7.4）。
     */
    suspend fun seam(name: String): Any? {
        val declared = kernel.currentCapabilities
        if (name !in declared) {
            throw CordisCapabilityNotDeclaredException(
                "plugin '${kernel.currentPluginId}' attempted to access undeclared capability '$name'; " +
                    "declared: $declared"
            )
        }
        return get(name)
    }
}

/** 服务注册表项：Cordis 的 ctx.set(name, value)/ctx.get(name) */
internal class CordisService(
    val name: String,
    val value: Any?,
    val pluginId: String,
)

/** 注册的命令声明：ctx.command(name, body, action) */
class CordisCommand(
    val name: String,
    val body: String = "",
    val pluginId: String,
    /** 命令执行器：action 回调（Kotlin 或经 JS 桥接的 JS 函数） */
    val action: (suspend (JsonObject) -> JsonObject)? = null,
)

/**
 * Cordis 内核：root ctx + 事件总线 + 插件加载器（inject 拓扑排序）+ 宿主能力缝。
 */
class CordisKernel(
    private val host: CordisHost = CordisHost(),
    eventBus: CordisEventBus = CordisEventBus(),
) {
    val eventBus = eventBus

    private val root = CordisContext(this, null)
    private val contexts = mutableListOf<CordisContext>()
    private val plugins = mutableListOf<CordisPlugin>()
    private var disposed = false

    @Volatile
    internal var currentPluginId: String = "core"

    @Volatile
    internal var currentCapabilities: Set<String> = emptySet()

    private val _pluginsState = MutableStateFlow<List<String>>(emptyList())
    val pluginsState: StateFlow<List<String>> = _pluginsState.asStateFlow()

    val rootContext: CordisContext
        get() {
            check(!disposed) { "kernel disposed" }
            return root
        }

    init {
        registerHostSeams()
    }

    /** 把宿主提供的能力缝注册为 root ctx 服务（仅注册非空能力）。 */
    private fun registerHostSeams() {
        currentPluginId = "core"
        currentCapabilities = emptySet()
        host.llm?.let { root.set("llm", it) }
        host.tools?.let { root.set("tools", it) }
        host.sessions?.let { root.set("sessions", it) }
        host.systemPrompt?.let { root.set("systemPrompt", it) }
        host.fs?.let { root.set("fs", it) }
        host.sandbox?.let { root.set("sandbox", it) }
        host.subprocess?.let { root.set("subprocess", it) }
        host.shell?.let { root.set("shell", it) }
        host.terminal?.let { root.set("terminal", it) }
        host.approval?.let { root.set("approval", it) }
        currentPluginId = "core"
        currentCapabilities = emptySet()
    }

    fun isDisposed(): Boolean = disposed

    internal fun createContext(parent: CordisContext?): CordisContext {
        check(!disposed) { "kernel disposed" }
        val ctx = CordisContext(this, parent)
        contexts += ctx
        return ctx
    }

    internal fun onContextDisposed(ctx: CordisContext) {
        contexts.remove(ctx)
    }

    /**
     * 注册插件：按 inject 依赖做拓扑排序后 apply。
     * 循环依赖抛出 [CordisCycleDependencyException]。
     */
    fun register(plugin: CordisPlugin) {
        check(!disposed) { "kernel disposed" }
        if (plugins.any { it.id == plugin.id }) error("plugin already registered: ${plugin.id}")
        topoOrder(plugins + plugin) // 先验证依赖图（含循环检测）
        plugins += plugin
        applyPlugin(plugin)
        _pluginsState.update { it + plugin.id }
    }

    /** 卸载插件：逆序执行其 effects，移除其服务/命令/模型并解绑监听。 */
    suspend fun unregister(pluginId: String) {
        val plugin = plugins.firstOrNull { it.id == pluginId } ?: return
        plugins.remove(plugin)
        root.disposePlugin(pluginId)
        _pluginsState.update { it.filterNot { id -> id == pluginId } }
    }

    /** 销毁内核：逆序回收全部插件作用域与 effects。 */
    suspend fun dispose() {
        plugins.asReversed().forEach { plugin ->
            root.disposePlugin(plugin.id)
        }
        plugins.clear()
        root.dispose()
        disposed = true
    }

    private fun applyPlugin(plugin: CordisPlugin) {
        currentPluginId = plugin.id
        currentCapabilities = plugin.capabilities.toSet()
        try {
            kotlinx.coroutines.runBlocking { plugin.apply(root) }
        } catch (e: Throwable) {
            root.disposePlugin(plugin.id)
            throw CordisPluginApplyException("plugin ${plugin.id} apply failed", e)
        } finally {
            currentPluginId = "core"
            currentCapabilities = emptySet()
        }
    }

    /** 依赖拓扑排序：inject 声明的依赖先加载；循环依赖抛异常。 */
    internal fun topoOrder(plugins: List<CordisPlugin>): List<CordisPlugin> {
        val index = plugins.withIndex().associate { (i, p) -> p.id to i }
        val visited = BooleanArray(plugins.size)
        val visiting = BooleanArray(plugins.size)
        val result = mutableListOf<CordisPlugin>()

        fun visit(i: Int) {
            if (visited[i]) return
            if (visiting[i]) {
                val cycle = plugins.filterIndexed { idx, _ -> visiting[idx] }.map { it.id }
                throw CordisCycleDependencyException("cycle dependency detected: $cycle")
            }
            visiting[i] = true
            val plugin = plugins[i]
            for (dep in plugin.inject) {
                val depIndex = index[dep]
                if (depIndex != null) visit(depIndex)
            }
            visiting[i] = false
            visited[i] = true
            result += plugin
        }

        plugins.indices.forEach { visit(it) }
        return result
    }
}

class CordisPluginApplyException(message: String, cause: Throwable) : RuntimeException(message, cause)

class CordisCycleDependencyException(message: String) : RuntimeException(message)

class CordisCapabilityNotDeclaredException(message: String) : RuntimeException(message)