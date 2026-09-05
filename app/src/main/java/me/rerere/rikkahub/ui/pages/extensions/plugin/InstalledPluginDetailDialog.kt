package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.plugin.CordisRuntimeHost
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginCapabilityPreflight
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginConfigRepository
import me.rerere.rikkahub.data.plugin.PluginStatus
import org.koin.compose.koinInject

/**
 * 已安装插件详情对话框：运行状态披露、能力（权限）清单、面板直达、
 * 配置编辑、启用开关与卸载。被插件市场页与技能页共用。
 */
@Composable
fun InstalledPluginDetailDialog(
    plugin: InstalledPlugin,
    installDir: String,
    onToggle: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
    onOpenPanel: (() -> Unit)? = null,
    updateVersion: String? = null,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val settingsStore: SettingsStore = koinInject()
    val pluginConfigRepository: PluginConfigRepository = koinInject()
    val settings by settingsStore.settingsFlow.collectAsState()
    var showConfig by remember { mutableStateOf(false) }
    val info = plugin.info
    val runState = remember(plugin.id, plugin.status) {
        pluginRunStateOf(plugin)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info?.name ?: plugin.id) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // 运行状态（含内核加载失败的可解释说明）
                RunStateSection(plugin = plugin, runState = runState)
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
                        if (updateVersion != null) {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "可更新 v$updateVersion",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                },
                            )
                        }
                    }
                    if (i.author.isNotBlank()) {
                        Text("作者：${i.author}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (i.repository.isNotBlank() && (i.repository.startsWith("https://") || i.repository.startsWith("http://"))) {
                        Text(
                            text = i.repository,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            maxLines = 1,
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
                // 面板直达：面板插件详情里给显式入口
                if (onOpenPanel != null) {
                    FilledTonalButton(onClick = onOpenPanel) {
                        Icon(HugeIcons.Puzzle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("打开面板", modifier = Modifier.padding(start = 4.dp))
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
            TextButton(onClick = onUninstall) {
                Icon(
                    HugeIcons.Delete01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text("卸载", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
            }
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

/** 详情对话框内的运行状态区：状态 + 加载失败解释 + 能力（权限）披露 */
@Composable
private fun RunStateSection(
    plugin: InstalledPlugin,
    runState: PluginRunState,
) {
    val info = plugin.info
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val statusColor = when (runState) {
            PluginRunState.RUNNING, PluginRunState.EFFECTIVE -> MaterialTheme.colorScheme.primary
            PluginRunState.LOAD_FAILED, PluginRunState.BROKEN -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = "状态：${runState.label}",
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
        )
        if (runState == PluginRunState.LOAD_FAILED) {
            Text(
                text = "插件已启用，但未能加载进运行环境（可能因依赖缺失或脚本错误）。可尝试重新开关一次；若持续失败请更新或重装插件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (runState == PluginRunState.RESOURCE_PACK) {
            Text(
                text = "该插件仅提供文件与提示词，无独立运行能力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 能力（权限）披露：supported 正常展示，unsupported 标灰并注明"暂不支持"
        val caps = remember(info?.tags) {
            info?.let {
                PluginCapabilityPreflight.check(
                    PluginCapabilityPreflight.requestedFromTags(it.tags),
                    CordisRuntimeHost.HOST_CAPABILITIES,
                )
            }
        }
        if (caps != null && caps.requested.isNotEmpty()) {
            Text("申请能力", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                caps.requested.take(6).forEach { cap ->
                    val supported = cap in caps.supported
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (supported) cap else "$cap（暂不支持）",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (supported) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                    )
                }
            }
            if (!caps.allSupported) {
                Text(
                    text = "灰色能力在当前宿主暂未实现，安装后调用会返回不可用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 对话框内不接运行时流，按安装态降级判定（运行态由卡片侧实时披露） */
private fun pluginRunStateOf(plugin: InstalledPlugin): PluginRunState = when {
    plugin.status == PluginStatus.BROKEN -> PluginRunState.BROKEN
    plugin.status == PluginStatus.ENABLED && !hasCapability(plugin) -> PluginRunState.RESOURCE_PACK
    plugin.status == PluginStatus.ENABLED -> PluginRunState.EFFECTIVE
    hasCapability(plugin) -> PluginRunState.STOPPED
    else -> PluginRunState.INSTALLED
}

private fun hasCapability(plugin: InstalledPlugin): Boolean {
    val info = plugin.info ?: return false
    return info.systemPrompt.isNotBlank() ||
        info.actions.isNotEmpty() ||
        info.hooks.isNotEmpty() ||
        info.extensionPoints.homeActions.isNotEmpty() ||
        info.extensionPoints.settingsActions.isNotEmpty() ||
        info.extensionPoints.sidebarActions.isNotEmpty() ||
        info.extensionPoints.chatToolbarActions.isNotEmpty() ||
        info.extensionPoints.inputBarActions.isNotEmpty()
}
