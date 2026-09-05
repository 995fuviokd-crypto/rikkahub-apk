package me.rerere.androidvm.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.ShieldBan
import com.composables.icons.lucide.Trash2
import me.rerere.androidvm.R
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmInstance
import me.rerere.androidvm.VmModuleInfo
import me.rerere.androidvm.VmModuleKind
import me.rerere.androidvm.VmVM
import me.rerere.androidvm.navigation.VmNavigator
import java.io.File

/**
 * 实例详情：应用管理、虚拟 root / Magisk、悬浮窗、息屏保活。
 */
@Composable
fun VmDetailPage(
    vm: VmVM,
    navigator: VmNavigator,
    instanceId: String,
    onOpenTerminal: (String) -> Unit,
    onOpenWorkspace: (String) -> Unit = {},
) {
    val instance = vm.instances.firstOrNull { it.id == instanceId }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message.value) {
        vm.message.value?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }
    if (instance == null) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(R.string.vm_detail_not_found_title)) }) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(it)) {
                Text(stringResource(R.string.vm_detail_not_found_body), modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
            }
        }
        return
    }

    val isAndroid = instance.engineType == VmEngineType.ANDROID
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val tmp = File(context.cacheDir, "vm_apk_${System.currentTimeMillis()}.apk")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            vm.installApp(instance, tmp.absolutePath)
        }.onFailure { vm.message.value = context.getString(R.string.vm_msg_read_apk_failed, it.message) }
    }
    // Xposed 模块本身是 APK; Bcore 不支持 Magisk zip 模块
    val modulePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val tmp = File(context.cacheDir, "vm_module_${System.currentTimeMillis()}.apk")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            vm.installModule(instance, tmp.absolutePath)
        }.onFailure { vm.message.value = context.getString(R.string.vm_msg_read_module_failed, it.message) }
    }
    LaunchedEffect(instanceId) {
        if (isAndroid) vm.loadModules()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(instance.name) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Lucide.ArrowLeft, null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                Text(
                    instance.systemLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // 运行环境入口
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vm_runtime_env), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (isAndroid) {
                        FilledTonalButton(onClick = { picker.launch("application/vnd.android.package-archive") }) {
                            Icon(Lucide.Download, null)
                            Text(stringResource(R.string.vm_install_apk))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { onOpenTerminal(instance.id) }) {
                                Icon(Lucide.Play, null)
                                Text(stringResource(R.string.vm_open_terminal))
                            }
                            OutlinedButton(onClick = { onOpenWorkspace(instance.id) }) {
                                Icon(Lucide.FolderOpen, null)
                                Text(stringResource(R.string.vm_open_workspace))
                            }
                        }
                    }
                }
            }

            // 已安装应用（Android 虚拟化）
            if (isAndroid) {
                item {
                    Text(stringResource(R.string.vm_installed_apps), style = MaterialTheme.typography.titleSmall)
                }
                if (instance.installedApps.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.vm_no_apps_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    items(instance.installedApps.size) { idx ->
                        val pkg = instance.installedApps[idx]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(pkg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { vm.launch(instance, pkg) }) {
                                Icon(Lucide.Play, null)
                                Text(stringResource(R.string.vm_launch))
                            }
                        }
                    }
                }
            }

            item { HorizontalDivider() }

            // 仅 Android 虚拟化支持的能力
            item {
                VmSwitchRow(
                    icon = Lucide.Shield,
                    title = stringResource(R.string.vm_virtual_root),
                    subtitle = if (isAndroid) stringResource(R.string.vm_virtual_root_desc_android) else stringResource(R.string.vm_android_only_desc),
                    checked = instance.virtualRoot,
                    enabled = isAndroid,
                    onChecked = { vm.toggleVirtualRoot(instance, it) },
                )
            }
            item {
                VmSwitchRow(
                    icon = Lucide.EyeOff,
                    title = stringResource(R.string.vm_hide_root),
                    subtitle = if (isAndroid) stringResource(R.string.vm_hide_root_desc_android) else stringResource(R.string.vm_android_only_desc),
                    checked = instance.hideRoot,
                    enabled = isAndroid,
                    onChecked = { vm.toggleHideRoot(instance, it) },
                )
            }
            item {
                VmSwitchRow(
                    icon = Lucide.ShieldBan,
                    title = stringResource(R.string.vm_hide_xposed),
                    subtitle = if (isAndroid) stringResource(R.string.vm_hide_xposed_desc_android) else stringResource(R.string.vm_android_only_desc),
                    checked = instance.hideXposed,
                    enabled = isAndroid,
                    onChecked = { vm.toggleHideXposed(instance, it) },
                )
            }
            if (isAndroid) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.vm_magisk_modules), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = { modulePicker.launch("application/vnd.android.package-archive") }) {
                            Icon(Lucide.Download, null)
                            Text(stringResource(R.string.vm_flash_magisk))
                        }
                    }
                }
                if (vm.modules.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.vm_no_modules_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    items(vm.modules.size) { idx ->
                        val mod = vm.modules[idx]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(mod.name.ifBlank { mod.moduleId }, style = MaterialTheme.typography.bodyMedium)
                                    if (mod.kind == VmModuleKind.MAGISK) {
                                        Text(
                                            stringResource(R.string.vm_badge_magisk),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                }
                                Text(
                                    buildModuleSubtitle(mod),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Switch(checked = mod.enabled, onCheckedChange = { vm.setModuleEnabled(mod.moduleId, it) })
                            IconButton(onClick = { vm.uninstallModule(mod.moduleId) }) {
                                Icon(Lucide.Trash2, null)
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.vm_restart_vm), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.restart(instance) }) {
                            Icon(Lucide.Power, null)
                            Text(stringResource(R.string.vm_restart))
                        }
                    }
                }
            }
            item {
                VmSwitchRow(
                    icon = Lucide.Maximize,
                    title = stringResource(R.string.vm_floating_window),
                    subtitle = if (isAndroid) stringResource(R.string.vm_floating_window_desc_android) else stringResource(R.string.vm_android_only_desc),
                    checked = instance.floatingWindow,
                    enabled = isAndroid,
                    onChecked = { vm.toggleFloatingWindow(instance, it) },
                )
            }
            item {
                VmSwitchRow(
                    icon = Lucide.Power,
                    title = stringResource(R.string.vm_keep_alive),
                    subtitle = if (isAndroid) stringResource(R.string.vm_keep_alive_desc_android) else stringResource(R.string.vm_android_only_desc),
                    checked = instance.keepAlive,
                    enabled = isAndroid,
                    onChecked = { vm.toggleKeepAlive(instance, it) },
                )
            }

            item { HorizontalDivider() }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vm_danger_zone), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.delete(instance) }) {
                        Icon(Lucide.Trash2, null)
                        Text(stringResource(R.string.vm_delete_instance))
                    }
                }
            }
        }
    }
}

/** 模块副标题：Magisk 展示 v版本 · @作者 · 描述，Xposed 展示包名。 */
private fun buildModuleSubtitle(mod: VmModuleInfo): String {
    if (mod.kind == VmModuleKind.MAGISK) {
        val head = listOfNotNull(
            mod.version.takeIf { it.isNotBlank() }?.let { "v$it" },
            mod.author.takeIf { it.isNotBlank() }?.let { "@$it" },
        ).joinToString(" ")
        return listOfNotNull(
            head.takeIf { it.isNotBlank() },
            mod.description.takeIf { it.isNotBlank() },
            mod.moduleId,
        ).joinToString(" · ")
    }
    return mod.moduleId
}

@Composable
private fun VmSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}
