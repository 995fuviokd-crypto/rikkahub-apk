package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.provider.AgentPlatform

/** 单条安装日志行 */
data class AgentInstallLogLine(
    val time: Long,
    val text: String,
    val isError: Boolean = false,
)

/** 某个平台 Agent 最近一次安装的完整日志快照 */
data class AgentInstallLog(
    val root: String = "",
    val platform: AgentPlatform? = null,
    /** 安装是否仍在进行中 */
    val active: Boolean = false,
    val lines: List<AgentInstallLogLine> = emptyList(),
)

/**
 * Agent 安装日志总线: AcpEnvironmentManager 在安装过程中把命令输出实时写入,
 * 「工作区终端」页面订阅后以滚动日志形式展示, 让用户能看到 npm/curl 的真实下载与安装过程。
 *
 * 线程模型: append 来自 IO 线程的流式回调, 读取来自主线程, 统一用锁保护内部状态。
 * 流式 chunk 会先进入行缓冲, 凑成完整行后才提交, 避免半行刷新导致 UI 抖动。
 */
class AgentInstallLogBus {
    private val lock = Any()
    private val partialLines = mutableMapOf<AgentPlatform, StringBuilder>()
    private val _logs = MutableStateFlow<Map<AgentPlatform, AgentInstallLog>>(emptyMap())
    val logs: StateFlow<Map<AgentPlatform, AgentInstallLog>> = _logs.asStateFlow()

    /** 开始一次新的安装: 清空该平台旧日志并标记为进行中 */
    fun begin(root: String, platform: AgentPlatform) {
        synchronized(lock) {
            partialLines.remove(platform)
            _logs.value = _logs.value + (platform to AgentInstallLog(root = root, platform = platform, active = true))
        }
    }

    /** 追加流式输出片段(可能是不完整的一行), 内部按换行切分后提交 */
    fun append(platform: AgentPlatform, chunk: String, isError: Boolean = false) {
        if (chunk.isEmpty()) return
        val committed = mutableListOf<AgentInstallLogLine>()
        var flushPartialAsError = false
        synchronized(lock) {
            val buffer = partialLines.getOrPut(platform) { StringBuilder() }
            chunk.forEach { char ->
                when {
                    char == '\n' -> {
                        committed += AgentInstallLogLine(
                            time = System.currentTimeMillis(),
                            text = buffer.toString(),
                            isError = isError || flushPartialAsError,
                        )
                        buffer.clear()
                        flushPartialAsError = false
                    }
                    char != '\r' -> buffer.append(char)
                }
            }
            // 错误输出通常没有尾随换行(如 npm 的 stderr), 缓冲区超长时强制落一行避免滞留
            if (isError && buffer.length > MAX_PARTIAL_CHARS) {
                committed += AgentInstallLogLine(
                    time = System.currentTimeMillis(),
                    text = buffer.toString(),
                    isError = true,
                )
                buffer.clear()
            }
            if (committed.isEmpty()) return
            updateLog(platform) { log ->
                log.copy(lines = truncate(log.lines + committed))
            }
        }
    }

    /** 结束安装: 刷出残留的半行并取消进行中标记 */
    fun finish(platform: AgentPlatform) {
        synchronized(lock) {
            val remaining = partialLines.remove(platform)
            updateLog(platform) { log ->
                val tail = remaining?.takeIf { it.isNotEmpty() }?.let {
                    listOf(AgentInstallLogLine(System.currentTimeMillis(), it.toString()))
                }.orEmpty()
                log.copy(active = false, lines = truncate(log.lines + tail))
            }
        }
    }

    fun clear(platform: AgentPlatform) {
        synchronized(lock) {
            partialLines.remove(platform)
            _logs.value = _logs.value - platform
        }
    }

    private inline fun updateLog(
        platform: AgentPlatform,
        transform: (AgentInstallLog) -> AgentInstallLog,
    ) {
        val current = _logs.value[platform] ?: AgentInstallLog(platform = platform)
        _logs.value = _logs.value + (platform to transform(current))
    }

    private fun truncate(lines: List<AgentInstallLogLine>): List<AgentInstallLogLine> =
        if (lines.size <= MAX_LINES) lines else lines.takeLast(MAX_LINES)

    private companion object {
        /** 每个平台保留的最大行数, 防止超大构建日志撑爆内存 */
        const val MAX_LINES = 400

        /** 无换行错误输出的最大缓冲字符数 */
        const val MAX_PARTIAL_CHARS = 2000
    }
}
