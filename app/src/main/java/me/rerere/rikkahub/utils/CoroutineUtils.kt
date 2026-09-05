package me.rerere.rikkahub.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CoroutineUtils"
private const val MAX_COLLECT_RETRIES = 3

/**
 * 收集上游 Flow 并镜像到 MutableStateFlow。
 *
 * 失败策略：有限重试（指数退避），全部失败后保持 [initial] 降级值并记录日志。
 * 曾经的实现是直接 halt 进程（避免带病运行），但 DataStore 单次 IO 异常即闪退
 * 对用户不可接受，且可能形成启动循环，故改为降级 + 等待下次触发。
 */
fun <T> Flow<T>.toMutableStateFlow(
    scope: CoroutineScope,
    initial: T
): MutableStateFlow<T> {
    val stateFlow = MutableStateFlow(initial)
    scope.launch {
        var attempt = 0
        while (true) {
            try {
                this@toMutableStateFlow.collect { value ->
                    stateFlow.value = value
                }
                // collect 正常结束（上游完成）：不再重试
                return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                Log.e(TAG, "Error while collecting flow (attempt $attempt): ${e.message}", e)
                if (attempt >= MAX_COLLECT_RETRIES) {
                    Log.e(TAG, "Flow collection failed $attempt times, degrading to initial value")
                    return@launch
                }
                delay(1000L * attempt)
            }
        }
    }
    return stateFlow
}
