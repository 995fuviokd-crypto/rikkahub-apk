package me.rerere.rikkahub.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.DeviceScreenMetrics

/**
 * 屏幕显示缩放根组件。
 *
 * 当用户启用"平板"/"自定义"显示缩放时，在 Compose 组合树根部覆盖
 * [LocalConfiguration] 与 [LocalDensity]：整个 app 按目标像素密度真实重新布局，
 * 逻辑屏幕 dp 变大，手机上即呈现平板大小的布局。恢复模式下不做任何覆盖。
 *
 * 该实现是纯软件渲染（不修改系统设置、不需要 root 或重启），
 * 所有读取 LocalDensity / LocalConfiguration 的组件都会真实响应。
 */
@Composable
fun DisplayScaleProvider(
    settingsStore: SettingsStore,
    content: @Composable () -> Unit,
) {
    val settings by settingsStore.settingsFlow
        .collectAsStateWithLifecycle(initialValue = Settings.dummy())
    val baseConfig = LocalConfiguration.current
    val baseDensity = LocalDensity.current
    val overrideConfig = remember(
        settings.displayScaleMode,
        settings.displayScaleDensityDpi,
        baseConfig,
    ) {
        DeviceScreenMetrics.buildOverrideConfiguration(
            base = baseConfig,
            mode = settings.displayScaleMode,
            customDpi = settings.displayScaleDensityDpi,
        )
    }

    if (overrideConfig != null) {
        val overrideDensity = Density(
            density = overrideConfig.densityDpi / 160f,
            fontScale = baseDensity.fontScale,
        )
        CompositionLocalProvider(
            LocalConfiguration provides overrideConfig,
            LocalDensity provides overrideDensity,
        ) {
            content()
        }
    } else {
        content()
    }
}
