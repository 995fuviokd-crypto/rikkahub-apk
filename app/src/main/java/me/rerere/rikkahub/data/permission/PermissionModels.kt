package me.rerere.rikkahub.data.permission

import kotlinx.serialization.Serializable

/**
 * 权限层级：从低到高。
 */
@Serializable
enum class PermissionLevel {
    NONE,
    ACCESSIBILITY,
    ADB,
    ROOT,
}

/**
 * 命令执行结果。
 */
data class ChannelResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val success: Boolean get() = exitCode == 0
}

/**
 * 审计日志条目。
 */
@Serializable
data class AuditEntry(
    val time: Long,
    val level: String,
    val action: String,
    val summary: String,
)
