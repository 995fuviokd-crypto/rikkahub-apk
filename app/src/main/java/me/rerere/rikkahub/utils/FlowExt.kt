package me.rerere.rikkahub.utils

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

// 流式生成期间 UI 更新的节流窗口：合并高频 delta，仅保留窗口内最新一帧，
// 把聊天列表/悬浮球的重组频率从「每 delta 一次」降为「每窗口一次」，
// 长对话下显著降低主线程负载，打字机视觉效果不受影响。
const val STREAM_UI_THROTTLE_MS = 50L

/**
 * 保留窗口内最新值的节流：上游高频发值时，窗口内只发出第一帧并合并后续为最新一帧，
 * 上游完成后立即结束。用于流式生成的 UI 收集，避免每 token 全量重组。
 */
fun <T> Flow<T>.throttleLatest(windowMillis: Long): Flow<T> = flow {
    val conflated = Channel<T>(Channel.CONFLATED)
    coroutineScope {
        launch {
            try {
                collect { conflated.send(it) }
            } finally {
                conflated.close()
            }
        }
        while (isActive) {
            val value = conflated.tryReceive().getOrNull() ?: try {
                conflated.receive()
            } catch (e: ClosedReceiveChannelException) {
                break
            }
            emit(value)
            delay(windowMillis)
            conflated.tryReceive().getOrNull()?.let { emit(it) }
        }
    }
}
