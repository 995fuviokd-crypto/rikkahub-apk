package me.rerere.rikkahub.data.ai.plan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger

/**
 * 计划追踪器：让模型通过内置 plan 工具维护结构化任务清单（类似 TodoWrite）。
 *
 * 生命周期：每次主生成开始前重置上一轮计划；生成过程中模型可随时 create / update 条目。
 * 状态通过 [state] 暴露给 UI：输入框上方的计划胶囊展示进行中条目的简短摘要。
 */
class PlanTracker {
    @Serializable
    data class PlanEntry(
        val id: String,
        val content: String,
        val status: String = "pending", // pending | in_progress | completed
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private val _state = MutableStateFlow<List<PlanEntry>>(emptyList())
    val state: StateFlow<List<PlanEntry>> = _state.asStateFlow()

    private val mutex = Mutex()
    private val idCounter = AtomicInteger(0)

    /** 生成开始：重置上一轮计划 */
    suspend fun reset() {
        mutex.withLock {
            idCounter.set(0)
            _state.value = emptyList()
        }
    }

    /** 创建新计划条目；返回条目 ID（模型用其在后续 update 中引用） */
    suspend fun create(content: String): String {
        val id = "plan-${idCounter.incrementAndGet()}"
        val now = System.currentTimeMillis()
        mutex.withLock {
            _state.value = _state.value + PlanEntry(
                id = id,
                content = content,
                createdAt = now,
                updatedAt = now,
            )
        }
        return id
    }

    /** 更新既有条目状态或内容；条目不存在时静默忽略（避免模型编造 ID） */
    suspend fun update(
        id: String,
        status: String? = null,
        content: String? = null,
    ) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            _state.value = _state.value.map { entry ->
                if (entry.id == id) {
                    entry.copy(
                        status = status ?: entry.status,
                        content = content ?: entry.content,
                        updatedAt = now,
                    )
                } else {
                    entry
                }
            }
        }
    }

    init {
        // 恢复默认状态（进程重建时）
        _state.value = emptyList()
    }
}