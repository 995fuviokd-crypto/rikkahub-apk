package me.rerere.rikkahub.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 无障碍服务桥接单例：App 与 [RikkaAccessibilityService] 运行在同一进程,
 * 工具侧通过该单例直接调用已连接的无障碍服务实例, 无需跨进程通信。
 */
object AccessibilityBridge {
    @Volatile
    var service: RikkaAccessibilityService? = null

    val isConnected: Boolean
        get() = service?.isReady == true

    fun requireService(): RikkaAccessibilityService =
        checkNotNull(service) { "Accessibility service is not connected" }
            .also { require(it.isReady) { "Accessibility service is not ready" } }
}

/**
 * 无障碍服务: 在系统无障碍设置中开启后, 为本地工具提供模拟点击/全局导航能力。
 */
class RikkaAccessibilityService : AccessibilityService() {

    @Volatile
    private var ready = false

    val isReady: Boolean get() = ready

    override fun onServiceConnected() {
        super.onServiceConnected()
        ready = true
        AccessibilityBridge.service = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // 事件由工具按需查询, 此处无需处理
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ready = false
        AccessibilityBridge.service = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ready = false
        AccessibilityBridge.service = null
        super.onDestroy()
    }

    /** 执行全局导航操作, 返回是否成功 */
    fun performGlobalNav(action: Int): Boolean = try {
        performGlobalAction(action)
    } catch (e: Exception) {
        toast(e.message)
        false
    }

    /** 查找屏幕上包含指定文本的节点, 并执行点击(优先 ACTION_CLICK, 否则模拟手势点击中心) */
    fun clickByText(text: String): Boolean {
        require(text.isNotBlank()) { "text is required" }
        val root = rootInActiveWindow ?: error("No active window, is the screen unlocked?")
        val candidates = root.findAccessibilityNodeInfosByText(text)
        root.recycle()
        val node = candidates.firstOrNull { it.isClickable } ?: candidates.firstOrNull()
            ?: error("No element with text '$text' found on screen")
        return clickNode(node)
    }

    /** 点击屏幕上坐标 [x]/[y](逻辑像素), 返回是否成功 */
    fun tap(x: Int, y: Int, durationMs: Long = 40): Boolean {
        require(x >= 0 && y >= 0) { "x/y must be >= 0" }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 长按屏幕上坐标 [x]/[y] */
    fun longPress(x: Int, y: Int): Boolean {
        require(x >= 0 && y >= 0) { "x/y must be >= 0" }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 700L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 从坐标 [x1]/[y1] 滑动到 [x2]/[y2] */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            val handled = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (handled) {
                node.recycle()
                true
            } else {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                node.recycle()
                val centerX = rect.exactCenterX().toInt()
                val centerY = rect.exactCenterY().toInt()
                tap(centerX, centerY)
            }
        } catch (e: Exception) {
            node.recycle()
            toast(e.message)
            false
        }
    }

    private fun toast(message: String?) {
        if (message.isNullOrBlank()) return
        runCatching {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
