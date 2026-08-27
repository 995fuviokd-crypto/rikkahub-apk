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
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Trash2
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmInstance
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
            topBar = { TopAppBar(title = { Text("实例不存在") }) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(it)) {
                Text("未找到该虚拟机实例", modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
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
        }.onFailure { vm.message.value = "读取 APK 失败：${it.message}" }
    }
    val modulePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val tmp = File(context.cacheDir, "vm_magisk_${System.currentTimeMillis()}.zip")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            vm.installModule(instance, tmp.absolutePath)
        }.onFailure { vm.message.value = "读取模块失败：${it.message}" }
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
                    Text("运行环境", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (isAndroid) {
                        FilledTonalButton(onClick = { picker.launch("application/vnd.android.package-archive") }) {
                            Icon(Lucide.Download, null)
                            Text("安装 APK")
                        }
                    } else {
                        FilledTonalButton(onClick = { onOpenTerminal(instance.id) }) {
                            Icon(Lucide.Play, null)
                            Text("打开终端")
                        }
                    }
                }
            }

            // 已安装应用（Android 虚拟化）
            if (isAndroid) {
                item {
                    Text("已安装应用", style = MaterialTheme.typography.titleSmall)
                }
                if (instance.installedApps.isEmpty()) {
                    item {
                        Text(
                            "尚未安装应用，点击上方「安装 APK」",
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
                                Text("启动")
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
                    title = "虚拟 Root（su 支持）",
                    subtitle = if (isAndroid) "在虚拟空间内提供虚拟 su，应用可见 root（非真 root）" else "仅 Android 虚拟化支持",
                    checked = instance.virtualRoot,
                    enabled = isAndroid,
                    onChecked = { vm.toggleVirtualRoot(instance, it) },
                )
            }
            if (isAndroid) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Magisk / 模块", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = { modulePicker.launch("*/*") }) {
                            Icon(Lucide.Download, null)
                            Text("刷入 Magisk")
                        }
                    }
                }
                if (vm.modules.isEmpty()) {
                    item {
                        Text(
                            "尚未刷入模块，点击「刷入 Magisk」选择 Magisk/Xposed 模块",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    items(vm.modules.size) { idx ->
                        val mod = vm.modules[idx]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(mod.name.ifBlank { mod.packageName }, style = MaterialTheme.typography.bodyMedium)
                                Text(mod.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Switch(checked = mod.enabled, onCheckedChange = { vm.setModuleEnabled(mod.packageName, it) })
                            IconButton(onClick = { vm.uninstallModule(mod.packageName) }) {
                                Icon(Lucide.Trash2, null)
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("重启虚拟机", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.restart(instance) }) {
                            Icon(Lucide.Power, null)
                            Text("重启")
                        }
                    }
                }
            }
            item {
                VmSwitchRow(
                    icon = Lucide.Maximize,
                    title = "悬浮窗小窗",
                    subtitle = if (isAndroid) "以悬浮窗形式运行" else "仅 Android 虚拟化支持",
                    checked = instance.floatingWindow,
                    enabled = isAndroid,
                    onChecked = { vm.toggleFloatingWindow(instance, it) },
                )
            }
            item {
                VmSwitchRow(
                    icon = Lucide.Power,
                    title = "息屏保活",
                    subtitle = if (isAndroid) "后台持续运行" else "仅 Android 虚拟化支持",
                    checked = instance.keepAlive,
                    enabled = isAndroid,
                    onChecked = { vm.toggleKeepAlive(instance, it) },
                )
            }

            item { HorizontalDivider() }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("危险操作", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.delete(instance) }) {
                        Icon(Lucide.Trash2, null)
                        Text("删除实例")
                    }
                }
            }
        }
    }
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
