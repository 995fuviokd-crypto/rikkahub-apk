package me.rerere.rikkahub.data.permission

import android.content.Context

/**
 * 虚拟屏幕（无头后台自动化）分层控制器。
 * - ADB 级（无 root）：后台命令、输入注入、应用启动；目标应用可见，不创建虚拟显示器。
 * - Root 级：以离屏窗口模式（windowingMode 0）在后台运行目标应用，不干扰主屏。
 */
class HeadlessController(private val context: Context) {
    private val manager by lazy { PermissionManager(context) }

    /** 依据权限层级返回无头能力是否可用。 */
    suspend fun isHeadlessAvailable(): Boolean = manager.currentLevel() >= PermissionLevel.ADB

    suspend fun supportLevel(): String = when {
        manager.rootReady() -> "root 完整虚拟显示"
        manager.shizukuReady() -> "ADB 级（后台命令与输入注入，应用可见）"
        else -> "无"
    }

    private suspend fun channelOrNull(): CommandChannel? = manager.currentChannel()

    private suspend fun noChannelResult(): ChannelResult =
        ChannelResult(-1, "", "无可用命令通道：请先启用 Shizuku（推荐）或 Root 权限")

    /** 启动应用；hidden=true 时请求离屏窗口模式（仅 root）。 */
    suspend fun launchApp(packageName: String, hidden: Boolean = false): ChannelResult {
        val channel = channelOrNull() ?: return noChannelResult()
        val root = manager.rootReady()
        val cmd = if (hidden && root) {
            "am start --windowingMode 0 -n $packageName"
        } else if (hidden) {
            return ChannelResult(-1, "", "无 root 权限时无法隐藏窗口，目标应用将正常显示")
        } else {
            "am start -n $packageName"
        }
        val result = channel.exec(cmd)
        manager.logAudit("adb_launch_app", "启动应用 $packageName${if (hidden) "（离屏）" else ""}", if (root) PermissionLevel.ROOT else PermissionLevel.ADB)
        return result
    }

    suspend fun stopApp(packageName: String): ChannelResult {
        val channel = channelOrNull() ?: return noChannelResult()
        val result = channel.exec("am force-stop $packageName")
        manager.logAudit("adb_launch_app", "停止应用 $packageName", PermissionLevel.ADB)
        return result
    }

    suspend fun inputTap(x: Int, y: Int): ChannelResult {
        val channel = channelOrNull() ?: return noChannelResult()
        val result = channel.exec("input tap $x $y")
        manager.logAudit("adb_input", "点击 ($x, $y)", PermissionLevel.ADB)
        return result
    }

    suspend fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): ChannelResult {
        val channel = channelOrNull() ?: return noChannelResult()
        val result = channel.exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
        manager.logAudit("adb_input", "滑动 ($x1,$y1)->($x2,$y2)", PermissionLevel.ADB)
        return result
    }

    suspend fun inputText(text: String): ChannelResult {
        val channel = channelOrNull() ?: return noChannelResult()
        val escaped = text.replace(" ", "%s")
        val result = channel.exec("input text '$escaped'")
        manager.logAudit("adb_input", "输入文本 ${text.take(20)}", PermissionLevel.ADB)
        return result
    }

    suspend fun runCommand(command: String, hidden: Boolean = false): ChannelResult {
        val channel = channelOrNull() ?: return noChannelResult()
        val result = channel.exec(command)
        manager.logAudit("adb_run_command", command.take(80), if (manager.rootReady()) PermissionLevel.ROOT else PermissionLevel.ADB)
        return result
    }

    suspend fun screenStatus(): ChannelResult {
        val channel = channelOrNull() ?: return noChannelResult()
        val result = channel.exec("dumpsys power | grep -E 'mWakefulness|mScreenOn'")
        manager.logAudit("adb_screen", "查询屏幕状态", PermissionLevel.ADB)
        return result
    }

    suspend fun screenCapture(path: String): ChannelResult {
        val channel = channelOrNull() ?: return noChannelResult()
        val result = channel.exec("screencap -p $path")
        manager.logAudit("adb_screen", "截屏到 $path", PermissionLevel.ADB)
        return result
    }
}
