package me.rerere.rikkahub.data.plugin

import android.util.Log

/**
 * 插件子系统结构化错误（D1.1/R1.2）。
 *
 * - [Unavailable]：依赖解析失败（Koin 缺失/构造异常）——插件环境整体不可用
 * - [ExecutionFailed]：插件执行失败（JS 崩溃、工具超时等）——单个插件/调用不可用
 * - [Invalid]：非法入参/状态（未声明能力缝、未知插件等）
 */
sealed class PluginSubsystemError(
    val what: String,
    cause: Throwable? = null,
) : RuntimeException("plugin error: $what", cause) {
    class Unavailable(what: String, cause: Throwable? = null) :
        PluginSubsystemError(what, cause)

    class ExecutionFailed(what: String, cause: Throwable? = null) :
        PluginSubsystemError(what, cause)

    class Invalid(what: String) :
        PluginSubsystemError(what)
}

/**
 * 插件子系统异常边界（D1.1/R1.2）。
 *
 * 插件子系统的全部对外入口（面板桥创建、面板 JS 桥注入、内核注册/卸载等）
 * 都应经 [PluginBoundary] 包裹：任何依赖解析失败、内核异常、执行错误都被
 * 转成 [PluginSubsystemError] 结构化结果，UI 依此呈现降级占位态而非崩溃。
 *
 * 使用方式：
 * ```
 * when (val r = PluginBoundary.guard("createJsBridge") { bridge.createJsBridge(id) }) {
 *     is PluginBoundary.Result.Ok -> r.value
 *     is PluginBoundary.Result.Err -> showPlaceholder(r.error)
 * }
 * ```
 */
object PluginBoundary {
    @PublishedApi
    internal const val TAG = "PluginBoundary"

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val error: PluginSubsystemError) : Result<Nothing>
    }

    /** 包裹依赖解析类入口：失败视为子系统不可用。 */
    inline fun <T> guard(what: String, block: () -> T): Result<T> = try {
        Result.Ok(block())
    } catch (t: Throwable) {
        Log.w(TAG, "$what failed", t)
        Result.Err(PluginSubsystemError.Unavailable(what, t))
    }

    /** 包裹执行类入口：失败视为单次执行失败（子系统仍可用）。 */
    inline fun <T> guardExecution(what: String, block: () -> T): Result<T> = try {
        Result.Ok(block())
    } catch (t: Throwable) {
        Log.w(TAG, "$what failed", t)
        Result.Err(PluginSubsystemError.ExecutionFailed(what, t))
    }

    /** 便捷映射：Ok 取值，Err 走 fallback。 */
    inline fun <T> Result<T>.getOrElse(fallback: (PluginSubsystemError) -> T): T = when (this) {
        is Result.Ok -> value
        is Result.Err -> fallback(error)
    }
}

/**
 * 安装期能力预检（R2.4）：用宿主能力清单（[CordisRuntimeHost.HOST_CAPABILITIES]）
 * 对照插件声明的能力缝，未实现项标灰供安装确认页披露；与 CordisJsBridge 的
 * `{"ok":false,"reason":"unimplemented"}` 运行时标记共用同一份清单。
 *
 * 纯 JVM 可测，不依赖 Android。
 */
object PluginCapabilityPreflight {

    data class PreflightResult(
        /** 插件声明的能力缝（解析自 tags 的 `cap:` 前缀） */
        val requested: List<String>,
        /** 宿主已实现（安装后可用） */
        val supported: List<String>,
        /** 宿主未实现（安装页标灰披露，运行时返回 unimplemented） */
        val unsupported: List<String>,
    ) {
        val allSupported: Boolean get() = unsupported.isEmpty()
    }

    /** 解析插件 tags 中的能力声明（`cap:llm` → `llm`）。 */
    fun requestedFromTags(tags: List<String>): List<String> =
        tags.mapNotNull { tag ->
            tag.takeIf { it.startsWith("cap:") }?.removePrefix("cap:")?.takeIf { it.isNotBlank() }
        }.distinct()

    /** 预检：requested 中不在 hostCapabilities 的标记为 unsupported。 */
    fun check(requested: List<String>, hostCapabilities: Set<String>): PreflightResult =
        PreflightResult(
            requested = requested,
            supported = requested.filter { it in hostCapabilities },
            unsupported = requested.filter { it !in hostCapabilities },
        )
}
