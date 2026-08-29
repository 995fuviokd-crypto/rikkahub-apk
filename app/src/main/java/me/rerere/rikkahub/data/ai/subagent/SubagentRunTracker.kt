package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import java.util.concurrent.atomic.AtomicInteger

/**
 * 子代理运行追踪器：记录当前生成任务中 delegate_subagent 工具的调用状态，
 * 供 UI（上下文小球旁的子代理徽章）展示"当前任务调用的子代理数量"与详情。
 *
 * 生命周期：每次主生成开始时 [beginGeneration] 清空上一轮记录；
 * 每个子代理调用通过 [recordStart]/[recordEnd] 登记。
 */
class SubagentRunTracker {
    @Serializable
    data class SubagentRun(
        val id: String,
        val modelId: String,
        val modelName: String,
        val promptSummary: String,
        val startedAt: Long,
        val endedAt: Long? = null,
        val success: Boolean? = null,
        val depth: Int = 0,
    ) {
        val isRunning: Boolean get() = endedAt == null
        val durationMs: Long? get() = endedAt?.let { it - startedAt }
    }

    data class TrackerState(
        val generationActive: Boolean = false,
        val runs: List<SubagentRun> = emptyList(),
    ) {
        val runningCount: Int get() = runs.count { it.isRunning }
        val totalCount: Int get() = runs.size
    }

    private val _state = MutableStateFlow(TrackerState())
    val state: StateFlow<TrackerState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val idCounter = AtomicInteger(0)
    private var generationDepth = 0

    /** 生成开始：顶层生成清空上一轮记录；嵌套生成（子代理自身生成）仅增加引用计数 */
    suspend fun beginGeneration() {
        mutex.withLock {
            if (generationDepth == 0) {
                idCounter.set(0)
                _state.value = TrackerState(generationActive = true)
            }
            generationDepth++
        }
    }

    /** 生成结束（无论成功失败）：引用计数归零时标记结束 */
    suspend fun endGeneration() {
        mutex.withLock {
            generationDepth = (generationDepth - 1).coerceAtLeast(0)
            if (generationDepth == 0) {
                _state.value = _state.value.copy(generationActive = false)
            }
        }
    }

    suspend fun recordStart(
        model: Model?,
        prompt: String,
        depth: Int = 0,
    ): String {
        val id = "sub-${idCounter.incrementAndGet()}"
        mutex.withLock {
            val run = SubagentRun(
                id = id,
                modelId = model?.modelId ?: "",
                modelName = model?.displayName ?: "",
                promptSummary = prompt.take(120),
                startedAt = System.currentTimeMillis(),
                depth = depth,
            )
            _state.value = _state.value.copy(runs = _state.value.runs + run)
        }
        return id
    }

    suspend fun recordEnd(id: String, success: Boolean) {
        mutex.withLock {
            _state.value = _state.value.copy(
                runs = _state.value.runs.map { run ->
                    if (run.id == id && run.isRunning) {
                        run.copy(
                            endedAt = System.currentTimeMillis(),
                            success = success,
                        )
                    } else {
                        run
                    }
                }
            )
        }
    }
}
