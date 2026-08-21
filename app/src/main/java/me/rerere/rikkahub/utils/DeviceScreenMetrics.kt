package me.rerere.rikkahub.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 设备屏幕显示工具。
 *
 * 提供"屏幕显示缩放"：当用户在偏好设置中启用缩放模式时，RikkaHub 会把整个 UI 按目标
 * 像素密度（densityDpi）真实渲染，从而在手机上呈现平板大小的布局（逻辑屏幕变宽变高）。
 * 这是纯软件实现——通过覆盖 Compose 的 LocalConfiguration / LocalDensity 驱动真实重排，
 * 即时生效，不需要 root、系统权限或重启。
 */
object DeviceScreenMetrics {

    /** 缩放模式：恢复（跟随设备） */
    const val MODE_NONE = 0

    /** 缩放模式：平板预设（典型 10 寸平板密度） */
    const val MODE_TABLET = 1

    /** 缩放模式：自定义密度 */
    const val MODE_CUSTOM = 2

    /** 平板预设使用的像素密度（默认 240：10 寸平板以 240dpi 呈现更大的逻辑屏幕） */
    const val DEFAULT_TABLET_DENSITY_DPI = 240

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
     * 读取设备真实像素密度（dpi）。
     */
    fun getRealDensityDpi(context: Context): Int {
        val appContext = context.applicationContext
        val metrics = DisplayMetrics()
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager != null) {
            runCatching {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
            }
        }
        val dpi = metrics.densityDpi
        return if (dpi > 0) dpi else appContext.resources.displayMetrics.densityDpi
    }

    /**
     * 根据模式返回目标像素密度；恢复模式或无有效自定义值时返回 null。
     */
    fun modeDensityDpi(mode: Int, customDpi: Int): Int? = when (mode) {
        MODE_TABLET -> DEFAULT_TABLET_DENSITY_DPI
        MODE_CUSTOM -> if (customDpi in 120..600) customDpi else null
        else -> null
    }

    /**
     * 读取有效像素密度：未启用缩放时返回设备真实密度。
     */
    suspend fun getEffectiveDensityDpi(
        context: Context,
        settingsStore: SettingsStore,
    ): Int {
        val settings = settingsStore.settingsFlow.first()
        return modeDensityDpi(settings.displayScaleMode, settings.displayScaleDensityDpi)
            ?: getRealDensityDpi(context)
    }

    /**
     * 基于系统真实 Configuration 构造覆盖后的 Configuration。
     * 在 [base] 基础上把 densityDpi 设为 [targetDpi]，并按同一物理窗口重新换算
     * screenWidthDp / screenHeightDp（密度越低，逻辑屏幕越大，即"平板效果"）。
     *
     * @return 覆盖后的配置；恢复模式（[modeDensityDpi] 为 null）返回 null。
     */
    fun buildOverrideConfiguration(base: Configuration, mode: Int, customDpi: Int): Configuration? {
        val targetDpi = modeDensityDpi(mode, customDpi) ?: return null
        val baseDpi = if (base.densityDpi > 0) base.densityDpi else 160
        val scale = baseDpi / targetDpi.toFloat()
        return Configuration(base).apply {
            densityDpi = targetDpi
            screenWidthDp = (base.screenWidthDp * scale).roundToInt().coerceAtLeast(320)
            screenHeightDp = (base.screenHeightDp * scale).roundToInt().coerceAtLeast(480)
            smallestScreenWidthDp = min(screenWidthDp, screenHeightDp)
        }
    }

    /**
     * 读取有效逻辑屏幕像素尺寸（供 AI 设备信息工具使用）：
     * 把设备真实物理分辨率按"有效密度 / 真实密度"换算，得到模拟后的屏幕规格。
     */
    suspend fun getEffectiveScreenSize(
        context: Context,
        settingsStore: SettingsStore,
    ): Pair<Int, Int> {
        val settings = settingsStore.settingsFlow.first()
        val targetDpi = modeDensityDpi(settings.displayScaleMode, settings.displayScaleDensityDpi)
            ?: return getRealScreenSize(context)
        val realDpi = getRealDensityDpi(context)
        if (realDpi <= 0) return getRealScreenSize(context)
        val scale = targetDpi / realDpi.toFloat()
        val (realWidth, realHeight) = getRealScreenSize(context)
        return (realWidth * scale).roundToInt() to (realHeight * scale).roundToInt()
    }
}
