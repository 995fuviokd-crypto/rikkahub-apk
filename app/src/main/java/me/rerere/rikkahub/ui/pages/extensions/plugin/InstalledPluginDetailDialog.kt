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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginStatus

/** 已安装插件详情对话框：展示元数据、启用开关、卸载。被插件市场页与技能页共用 */
@Composable
fun InstalledPluginDetailDialog(
    plugin: InstalledPlugin,
    installDir: String,
    onToggle: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val info = plugin.info
    val hasCapability = info != null && (
        info.systemPrompt.isNotBlank() ||
            info.actions.isNotEmpty() ||
            info.extensionPoints.homeActions.isNotEmpty() ||
            info.extensionPoints.settingsActions.isNotEmpty() ||
            info.extensionPoints.sidebarActions.isNotEmpty()
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
                }
                Text(
                    text = "安装位置：$installDir",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
}
