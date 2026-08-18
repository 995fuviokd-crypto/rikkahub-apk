package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.recall.RecallRecord
import me.rerere.rikkahub.data.recall.SideEffectLog
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 撤回历史栈（会话级、内存态）：连续撤回 / 连续恢复
    private val _recallHistory = MutableStateFlow<List<RecallRecord>>(emptyList())
    val recallHistory: StateFlow<List<RecallRecord>> = _recallHistory.asStateFlow()

    // nodeId -> 该 AI 回复产生的副作用 log（用于回滚）
    val sideEffectLogs = mutableMapOf<Uuid, SideEffectLog>()

    val canRedo: Boolean get() = _recallHistory.value.isNotEmpty()

    fun pushRecallRecord(record: RecallRecord) {
        _recallHistory.update { it + record }
    }

    fun popRecallRecord(): RecallRecord? {
        val list = _recallHistory.value
        if (list.isEmpty()) return null
        val last = list.last()
        _recallHistory.value = list.dropLast(1)
        return last
    }

    fun clearRecallRecords() {
        _recallHistory.value = emptyList()
    }

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun setJob(job: Job?) {
        _generationJob.value?.cancel()
        _generationJob.value = job
        job?.invokeOnCompletion {
            // 仅当仍是当前 job 时才清空：旧 job 被 cancel 后其完成回调可能晚于新 job 赋值，
            // 无条件置 null 会把新 job 清掉，导致 isGenerating 误判为 false 并可能错误清理 session
            if (_generationJob.value === job) {
                _generationJob.value = null
                if (refCount.get() <= 0) {
                    scheduleIdleCheck()
                }
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value?.cancel()
        _generationJob.value = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
