package me.rerere.workspace

import java.io.BufferedReader
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.withContext

class WorkspaceProcessSession internal constructor(
    private val process: Process,
) {
    private val closed = AtomicBoolean(false)
    private val stdoutChannel = Channel<String>(Channel.BUFFERED)
    private val stderrChannel = Channel<String>(Channel.BUFFERED)
    private val forwardThreads = mutableListOf<Thread>()

    init {
        forwardThreads.add(process.inputStream.forwardTo(stdoutChannel))
        forwardThreads.add(process.errorStream.forwardTo(stderrChannel))
    }

    private fun InputStream.forwardTo(channel: Channel<String>): Thread {
        val thread = Thread {
            try {
                bufferedReader().forEachLine { line ->
                    if (!closed.get()) channel.trySend(line)
                }
            } catch (_: Exception) {
            } finally {
                channel.close()
            }
        }.apply {
            isDaemon = true
            start()
        }
        return thread
    }

    val stdoutLines: Flow<String> = stdoutChannel.consumeAsFlow()

    val stderrLines: Flow<String> = stderrChannel.consumeAsFlow()

    suspend fun writeLine(line: String) {
        if (closed.get()) return
        withContext(Dispatchers.IO) {
            val out = process.outputStream
            out.write(line.encodeToByteArray() + '\n'.code.toByte())
            out.flush()
        }
    }

    val isAlive: Boolean
        get() = runCatching { process.isAlive }.getOrDefault(false)

    /**
     * Suspends until the process exits and returns its exit code (null on failure),
     * used by ACP `terminal/wait_for_exit`.
     */
    suspend fun waitForExit(): Int? = withContext(Dispatchers.IO) {
        runCatching { process.waitFor() }.getOrNull()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 先 destroy() 让子进程关闭 IO 流, 转发线程因流关闭自然终结;
        // 等 5 秒让线程退出(写日志/清理), 超时再强杀避免残留
        runCatching { process.destroy() }
        runCatching { process.waitFor(5, TimeUnit.SECONDS) }
        if (process.isAlive) {
            runCatching { process.destroyForcibly() }
        }
        forwardThreads.forEach { thread ->
            runCatching { thread.join(1000) }
        }
    }
}
