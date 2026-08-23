package me.rerere.workspace

import java.io.BufferedReader
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A long-lived process with a bidirectional stdio channel, used to drive ACP-style
 * agents (JSON-RPC over stdin/stdout) and other interactive CLIs inside a workspace.
 *
 * The process is started by [WorkspaceProcessRunner] and kept alive until [close].
 * Inbound output is forwarded line-by-line through [stdoutLines]; outbound messages are
 * written via [writeLine]. Thread-safety is handled by the underlying channels.
 */
class WorkspaceProcessSession internal constructor(
    private val process: Process,
) {
    private val closed = AtomicBoolean(false)
    private val lines = Channel<String>(Channel.UNLIMITED)

    init {
        process.inputStream.forwardLines()
    }

    private fun InputStream.forwardLines() {
        Thread {
            try {
                bufferedReader().forEachLine { line ->
                    if (!closed.get()) lines.trySend(line)
                }
            } catch (_: Exception) {
                // process exited / stream closed
            } finally {
                lines.close()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Stream of stdout lines produced by the process. */
    val stdoutLines: Flow<String> = lines.consumeAsFlow()

    /** Writes a single line (terminated by `\n`) to the process stdin. */
    suspend fun writeLine(line: String) {
        if (closed.get()) return
        withContext(Dispatchers.IO) {
            val out = process.outputStream
            out.write(line.encodeToByteArray() + '\n'.code.toByte())
            out.flush()
        }
    }

    /** Whether the underlying process is still alive. */
    val isAlive: Boolean
        get() = runCatching { process.isAlive }.getOrDefault(false)

    /** Forces the process to exit and closes the stream channel. */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { process.destroy() }
            runCatching { process.destroyForcibly() }
        }
    }

    fun stderrLines(): Flow<String> = flowOfStderr(process.errorStream)

    private fun flowOfStderr(stream: InputStream): Flow<String> = flow {
        val collector = this
        withContext(Dispatchers.IO) {
            stream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    collector.emit(line)
                }
            }
        }
    }
}
