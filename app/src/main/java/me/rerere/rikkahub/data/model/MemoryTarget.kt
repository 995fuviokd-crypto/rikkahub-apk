package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 记忆作用域目标（移植自 scope-recall-hermes 的 target 维度）。
 *
 * durable 目标（user/memory/project/ops）在相同助手/全局作用域内跨会话共享，
 * general 仅作为当前会话的本地暂存（scratch），不污染其他会话。
 */
@Serializable
enum class MemoryTarget(val durable: Boolean) {
    USER(true),
    MEMORY(true),
    PROJECT(true),
    OPS(true),
    GENERAL(false);

    companion object {
        fun fromString(value: String?): MemoryTarget =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MEMORY
    }
}
