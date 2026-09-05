package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.FloatingBubbleService
import me.rerere.rikkahub.service.KeepAliveService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.DeviceScreenMetrics
import me.rerere.rikkahub.utils.hasAccessibilityServiceEnabled
import me.rerere.rikkahub.utils.hasIgnoreBatteryOptimizationsPermission
import me.rerere.rikkahub.utils.openAccessibilitySettings
import me.rerere.rikkahub.utils.openBatteryOptimizationSettings
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 功能扩展页面：集中管理 AI 全能控制相关能力开关。
 *
 * 包含：保活通知 / 忽略电池优化 / 悬浮球 / 屏幕显示比例 / 自主执行任务 / 自动批准工具调用 /
 * 脚本工具 / 无障碍控制 / 电源管理。
 */
@Composable
fun SettingExtensionsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, settings.floatingBubbleEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                settings.floatingBubbleEnabled &&
                Settings.canDrawOverlays(context)
            ) {
                context.startForegroundService(
                    Intent(context, FloatingBubbleService::class.java)
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_extensions_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_extensions_ai_control)) },
                ) {
                    // 自主执行任务
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_extensions_autonomous_execution)) },
                        supportingContent = { Text(stringResource(R.string.setting_extensions_autonomous_execution_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.autonomousExecutionEnabled,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(autonomousExecutionEnabled = it))
                                }
                            )
                        },
                    )
                    // 自动批准工具调用
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_extensions_auto_approve_tools)) },
                        supportingContent = { Text(stringResource(R.string.setting_extensions_auto_approve_tools_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.autoApproveTools,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(autoApproveTools = it))
                                }
                            )
                        },
                    )
                    // 脚本工具（全局）
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_extensions_script_tools)) },
                        supportingContent = { Text(stringResource(R.string.setting_extensions_script_tools_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.globalToolScripts,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(globalToolScripts = it))
                                }
                            )
                        },
                    )
                    // 无障碍控制（全局）
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_extensions_accessibility)) },
                        supportingContent = { Text(stringResource(R.string.setting_extensions_accessibility_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.globalToolAccessibility,
                                onCheckedChange = { enabled ->
                                    if (enabled && !context.hasAccessibilityServiceEnabled()) {
                                        context.openAccessibilitySettings()
                                    }
                                    vm.updateSettings(settings.copy(globalToolAccessibility = enabled))
                                }
                            )
                        },
                    )
                    // 电源管理（全局）
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_extensions_power_management)) },
                        supportingContent = { Text(stringResource(R.string.setting_extensions_power_management_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.globalToolPowerManagement,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(globalToolPowerManagement = it))
                                }
                            )
                        },
                    )
                    // Termux 桥接（全局）
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_extensions_termux)) },
                        supportingContent = { Text(stringResource(R.string.setting_extensions_termux_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.globalToolTermux,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(globalToolTermux = it))
                                }
                            )
                        },
                    )
                    // 虚拟机控制（全局）
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_extensions_vm)) },
                        supportingContent = { Text(stringResource(R.string.setting_extensions_vm_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.globalToolVm,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(globalToolVm = it))
                                }
                            )
                        },
                    )
                    // 屏幕显示比例
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_display_scale_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_display_scale_desc)) },
                    )
                    item {
                        val modes = listOf(
                            DeviceScreenMetrics.MODE_NONE to stringResource(R.string.setting_display_page_display_scale_mode_none),
                            DeviceScreenMetrics.MODE_TABLET to stringResource(R.string.setting_display_page_display_scale_mode_tablet),
                            DeviceScreenMetrics.MODE_CUSTOM to stringResource(R.string.setting_display_page_display_scale_mode_custom),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            modes.forEach { (mode, label) ->
                                FilterChip(
                                    selected = settings.displayScaleMode == mode,
                                    onClick = {
                                        vm.updateSettings(settings.copy(displayScaleMode = mode))
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_extensions_keep_alive)) },
                ) {
                    // 保活通知
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_background_keep_alive_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_background_keep_alive_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.keepAliveEnabled,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(settings.copy(keepAliveEnabled = enabled))
                                    if (!enabled) {
                                        context.stopService(Intent(context, KeepAliveService::class.java))
                                    }
                                }
                            )
                        },
                    )
                    // 忽略电池优化
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_ignore_battery_optimizations_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_ignore_battery_optimizations_desc)) },
                        trailingContent = {
                            TextButton(
                                onClick = { context.openBatteryOptimizationSettings() },
                            ) {
                                Text(
                                    stringResource(
                                        if (context.hasIgnoreBatteryOptimizationsPermission()) {
                                            R.string.setting_page_ignore_battery_optimizations_done
                                        } else {
                                            R.string.setting_page_ignore_battery_optimizations_action
                                        }
                                    )
                                )
                            }
                        },
                    )
                    // 悬浮球
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_floating_bubble_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_floating_bubble_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.floatingBubbleEnabled,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(settings.copy(floatingBubbleEnabled = enabled))
                                    if (!enabled) {
                                        context.stopService(
                                            Intent(context, FloatingBubbleService::class.java)
                                        )
                                    } else if (Settings.canDrawOverlays(context)) {
                                        context.startForegroundService(
                                            Intent(context, FloatingBubbleService::class.java)
                                        )
                                    } else {
                                        showOverlayPermissionDialog = true
                                    }
                                }
                            )
                        },
                    )
                }
            }
        }
    }

    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            title = { Text(stringResource(R.string.setting_page_floating_bubble_permission_title)) },
            text = { Text(stringResource(R.string.setting_page_floating_bubble_permission_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOverlayPermissionDialog = false
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { context.startActivity(intent) }
                    }
                ) {
                    Text(stringResource(R.string.setting_page_floating_bubble_permission_grant))
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermissionDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
