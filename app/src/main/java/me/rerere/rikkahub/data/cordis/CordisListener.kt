package me.rerere.rikkahub.data.cordis

import kotlinx.serialization.json.JsonObject

/** 事件监听器注册项 */
class CordisListener(
    @Volatile var cancelled: Boolean = false,
    val handler: suspend (CordisEvent) -> JsonObject?,
    val pluginId: String? = null,
)

/** 事件监听句柄，用于注销 */
class CordisListenerHandle(
    internal val pattern: String,
    internal val listener: CordisListener,
) {
    /** 注销（幂等） */
    fun cancel() {
        listener.cancelled = true
    }
}