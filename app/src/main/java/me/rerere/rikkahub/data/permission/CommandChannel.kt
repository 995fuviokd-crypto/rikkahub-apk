package me.rerere.rikkahub.data.permission

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 命令执行通道：提供 ADB 级 shell 能力。
 * - [SuChannel]：通过 root su 执行；
 * - [ShizukuChannel]：通过 Shizuku（反射调用，无需编译期依赖）执行。
 */
interface CommandChannel {
    val name: String

    suspend fun exec(command: String, timeoutMs: Long = 30_000): ChannelResult
}

/**
 * 经 Shizuku 反射调用 Shizuku API。
 * 运行进程未集成 rikka.shizuku 库时 isLoaded() 返回 false，通道不可用。
 */
object ShizukuApi {
    private val shizukuClass by lazy {
        runCatching { Class.forName("rikka.shizuku.Shizuku") }.getOrNull()
    }

    fun isLoaded(): Boolean = shizukuClass != null

    /** Shizuku 服务是否已启动并连接。 */
    fun isAvailable(): Boolean {
        val clazz = shizukuClass ?: return false
        return runCatching {
            clazz.getField("SU_VERSION_NAME").get(null) != null
        }.getOrDefault(false)
    }

    /** 0 = 已授权，-1 = 未请求/被拒绝。 */
    fun checkSelfPermission(): Int {
        val clazz = shizukuClass ?: return -1
        return runCatching {
            clazz.getMethod("checkSelfPermission").invoke(null) as Int
        }.getOrDefault(-1)
    }

    fun requestPermission(): Boolean {
        val clazz = shizukuClass ?: return false
        return runCatching {
            clazz.getMethod("requestPermission", Int::class.java).invoke(null, 0) as Boolean
        }.getOrDefault(false)
    }

    fun newProcess(command: String): Process? {
        val clazz = shizukuClass ?: return null
        return runCatching {
            val method = clazz.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            val args = arrayOf("sh", "-c", command)
            method.invoke(null, args, null, null) as Process
        }.getOrNull()
    }
}

class ShizukuChannel : CommandChannel {
    override val name: String = "Shizuku"

    override suspend fun exec(command: String, timeoutMs: Long): ChannelResult =
        withContext(Dispatchers.IO) {
            if (!ShizukuApi.isAvailable()) {
                return@withContext ChannelResult(-1, "", "Shizuku 服务未运行或未授权，请先在 Shizuku App 中启动并授权")
            }
            val process = ShizukuApi.newProcess(command)
                ?: return@withContext ChannelResult(-1, "", "Shizuku 无法创建命令进程")
            runCatching {
                val stdout = readAll(process.inputStream, timeoutMs)
                val stderr = readAll(process.errorStream, timeoutMs)
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@runCatching ChannelResult(-1, stdout, "$stderr\n命令执行超时（${timeoutMs}ms）")
                }
                ChannelResult(process.exitValue(), stdout, stderr)
            }.getOrElse { e ->
                process.destroyForcibly()
                ChannelResult(-1, "", "Shizuku 执行失败：${e.message}")
            }
        }

    private suspend fun readAll(stream: java.io.InputStream, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(stream))
            val sb = StringBuilder()
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    sb.appendLine(line)
                }
            } catch (e: Exception) {
                // 超时或流关闭
            } finally {
                runCatching { stream.close() }
            }
            sb.toString().take(128 * 1024)
        }
}

class SuChannel : CommandChannel {
    override val name: String = "Root"

    override suspend fun exec(command: String, timeoutMs: Long): ChannelResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder("su", "-c", command).start()
                val stdout = readAll(process.inputStream)
                val stderr = readAll(process.errorStream)
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@runCatching ChannelResult(-1, stdout, "$stderr\n命令执行超时（${timeoutMs}ms）")
                }
                ChannelResult(process.exitValue(), stdout, stderr)
            }.getOrElse { e ->
                ChannelResult(-1, "", "Root 命令执行失败：${e.message}")
            }
        }

    private fun readAll(stream: java.io.InputStream): String =
        runCatching {
            val reader = BufferedReader(InputStreamReader(stream))
            val sb = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                sb.appendLine(line)
            }
            stream.close()
            sb.toString().take(128 * 1024)
        }.getOrDefault("")

    companion object {
        /** 检测 su 是否可用（只读验证 uid）。 */
        suspend fun detect(): Boolean = withContext(Dispatchers.IO) {
            val candidates = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")
            val fileExists = candidates.any { java.io.File(it).exists() }
            if (!fileExists) return@withContext false
            runCatching {
                val process = ProcessBuilder("su", "-c", "id").start()
                val out = process.inputStream.bufferedReader().readText()
                process.waitFor(5, TimeUnit.SECONDS)
                process.destroyForcibly()
                out.contains("uid=0")
            }.getOrDefault(false)
        }
    }
}
