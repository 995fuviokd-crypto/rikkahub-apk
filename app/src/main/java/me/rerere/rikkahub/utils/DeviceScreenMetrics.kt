package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Point
import android.view.WindowManager
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 设备屏幕尺寸读取工具。
 *
 * 提供"有效屏幕分辨率"的概念：当用户在偏好设置中启用屏幕分辨率覆盖时，
 * 所有 RikkaHub 内部读取屏幕宽高的地方都会返回伪造值（重启软件后依然持久生效）。
 * 未启用覆盖时返回设备的真实屏幕分辨率。
 */
object DeviceScreenMetrics {

    /**
     * 读取设备真实屏幕物理分辨率（像素）。
     */
    fun getRealScreenSize(context: Context): Pair<Int, Int> {
        val appContext = context.applicationContext
        val point = Point()
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager != null) {
            runCatching {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealSize(point)
            }.onFailure {
                point.set(0, 0)
            }
        }
        if (point.x <= 0 || point.y <= 0) {
            val metrics = appContext.resources.displayMetrics
            return metrics.widthPixels to metrics.heightPixels
        }
        return point.x to point.y
    }

    /**
     * 读取有效屏幕分辨率：覆盖启用且宽高均大于 0 时返回伪造值，否则返回真实值。
     */
    suspend fun getEffectiveScreenSize(
        context: Context,
        settingsStore: SettingsStore,
    ): Pair<Int, Int> {
        val settings = settingsStore.settingsFlow.first()
        if (settings.screenResolutionOverrideEnabled &&
            settings.screenResolutionOverrideWidth > 0 &&
            settings.screenResolutionOverrideHeight > 0
        ) {
            return settings.screenResolutionOverrideWidth to settings.screenResolutionOverrideHeight
        }
        return getRealScreenSize(context)
    }
}
