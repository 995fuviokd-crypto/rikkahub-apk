package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginConfigRepository
import me.rerere.rikkahub.data.plugin.PluginStatus
import org.koin.compose.koinInject

/** 已安装插件详情对话框：展示元数据、启用开关、配置编辑、卸载。被插件市场页与技能页共用 */
@Composable
fun InstalledPluginDetailDialog(
    plugin: InstalledPlugin,
    installDir: String,
    onToggle: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val settingsStore: SettingsStore = koinInject()
    val pluginConfigRepository: PluginConfigRepository = koinInject()
    val settings by settingsStore.settingsFlow.collectAsState()
    var showConfig by remember { mutableStateOf(false) }
    val info = plugin.info
    val hasCapability = info != null && (
        info.systemPrompt.isNotBlank() ||
            info.actions.isNotEmpty() ||
            info.hooks.isNotEmpty() ||
            info.extensionPoints.homeActions.isNotEmpty() ||
            info.extensionPoints.settingsActions.isNotEmpty() ||
            info.extensionPoints.sidebarActions.isNotEmpty() ||
            info.extensionPoints.chatToolbarActions.isNotEmpty() ||
            info.extensionPoints.inputBarActions.isNotEmpty()
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info?.name ?: plugin.id) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "状态：${when {
                        plugin.status == PluginStatus.BROKEN -> "损坏"
                        plugin.status == PluginStatus.ENABLED && !hasCapability -> "资源包（仅提供文件，无运行能力）"
                        plugin.status == PluginStatus.ENABLED -> "已生效"
                        hasCapability -> "未生效"
                        else -> "已安装"
                    }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                info?.let { i ->
                    if (i.description.isNotBlank()) {
                        Text(i.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text(PluginCategories.typeLabel(i.type), style = MaterialTheme.typography.labelSmall) },
                        )
                        if (i.version.isNotBlank()) {
                            AssistChip(
                                onClick = {},
                                label = { Text("v${i.version}", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    if (i.author.isNotBlank()) {
                        Text("作者：${i.author}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (i.repository.isNotBlank() && (i.repository.startsWith("https://") || i.repository.startsWith("http://"))) {
                        Text(
                            text = "GitHub 仓库",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { uriHandler.openUri(i.repository) },
                        )
                    }
                    if (i.tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            i.tags.take(4).forEach { tag ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                    if (i.hooks.isNotEmpty()) {
                        Text("动态 Hook", style = MaterialTheme.typography.labelLarge)
                        i.hooks.forEach { hook ->
                            Text(
                                text = buildString {
                                    append("• ")
                                    append(hook.name)
                                    if (hook.description.isNotBlank()) append("：${hook.description}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    text = "安装位置：$installDir",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info?.configSchema?.fields?.isNotEmpty() == true && plugin.status != PluginStatus.BROKEN) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { showConfig = true }) { Text("插件配置") }
                        Text(
                            text = "配置保存后立即对新的生成与 Hook 生效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (info != null && plugin.status != PluginStatus.BROKEN) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("启用", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = plugin.status == PluginStatus.ENABLED, onCheckedChange = { onToggle() })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUninstall) { Text("卸载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    if (showConfig && info?.configSchema != null) {
        PluginConfigDialog(
            plugin = plugin,
            currentConfig = settings.pluginConfigs[plugin.id],
            onSave = { json ->
                scope.launch {
                    val merged = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject }
                        .getOrNull() ?: return@launch
                    pluginConfigRepository.saveConfig(plugin.id, merged)
                    showConfig = false
                }
            },
            onReset = {
                scope.launch {
                    pluginConfigRepository.clearConfig(plugin.id)
                    showConfig = false
                }
            },
            onDismiss = { showConfig = false },
        )
    }
}
