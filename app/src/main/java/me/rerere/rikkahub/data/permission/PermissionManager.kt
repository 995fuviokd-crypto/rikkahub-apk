package me.rerere.rikkahub.data.permission

import android.content.Context
import me.rerere.rikkahub.service.accessibility.AccessibilityBridge
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference

/**
 * 权限状态检测与审计日志管理。
 */
class PermissionManager(private val context: Context) {

    /** 无障碍服务是否已开启。 */
    fun accessibilityReady(): Boolean = AccessibilityBridge.isConnected

    /** ADB 级能力：root su 可用，或 Shizuku 已授权。 */
    suspend fun adbReady(): Boolean = rootReady() || shizukuReady()

    /** root su 是否可用。 */
    suspend fun rootReady(): Boolean = SuChannel.detect()

    /** Shizuku 服务是否已连接且已授权。 */
    fun shizukuReady(): Boolean =
        ShizukuApi.isLoaded() && ShizukuApi.isAvailable() && ShizukuApi.checkSelfPermission() == 0

    /** 当前最高权限层级。 */
    suspend fun currentLevel(): PermissionLevel = when {
        rootReady() -> PermissionLevel.ROOT
        adbReady() -> PermissionLevel.ADB
        accessibilityReady() -> PermissionLevel.ACCESSIBILITY
        else -> PermissionLevel.NONE
    }

    /** 选择当前可用的命令通道：优先 root，其次 Shizuku。 */
    suspend fun currentChannel(): CommandChannel? = when {
        rootReady() -> SuChannel()
        shizukuReady() -> ShizukuChannel()
        else -> null
    }

    // ---------- 审计日志 ----------

    private val auditKey = "permission_audit_log"

    fun auditLogs(): List<AuditEntry> {
        val raw = context.readStringPreference(auditKey).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            JsonInstant.decodeFromString<List<AuditEntry>>(raw)
        }.getOrDefault(emptyList())
    }

    fun logAudit(action: String, summary: String, level: PermissionLevel) {
        val entry = AuditEntry(
            time = System.currentTimeMillis(),
            level = level.name,
            action = action,
            summary = summary,
        )
        val updated = (listOf(entry) + auditLogs()).take(100)
        context.writeStringPreference(auditKey, JsonInstant.encodeToString(updated))
    }

    fun clearAudit() {
        context.writeStringPreference(auditKey, "[]")
    }
}
