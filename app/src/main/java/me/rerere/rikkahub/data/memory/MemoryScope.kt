package me.rerere.rikkahub.data.memory

/**
 * 记忆作用域解析（移植自 scope-recall-hermes 的 scope.py）。
 *
 * Android 端为单机单用户，平台固定为 android、user 固定为 local；
 * durable 目标跨会话共享，general 暂存按 conversation 隔离。
 */
object MemoryScope {
    const val DURABLE = "durable"
    const val LOCAL = "local"
    const val GLOBAL_MEMORY_ID = "__global__"

    /** 根据助手是否使用全局记忆解析记忆归属 id。 */
    fun memoryAssistantId(useGlobalMemory: Boolean, assistantId: String): String =
        if (useGlobalMemory) GLOBAL_MEMORY_ID else assistantId

    fun isDurable(target: String): Boolean = target != "GENERAL"
}
