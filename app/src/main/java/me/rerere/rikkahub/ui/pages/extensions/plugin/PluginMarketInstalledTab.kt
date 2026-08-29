package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.rikkahub.data.ai.agent.AgentEnvStatus
import me.rerere.rikkahub.data.ai.agent.AgentInstallProgress
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginStatus
import me.rerere.rikkahub.ui.theme.CustomColors

/** 已安装插件 Tab：分类筛选 + Node 环境提醒 + 本地包安装入口 */
@Composable
internal fun InstalledTab(
    installed: List<InstalledPlugin>,
    envStatus: AgentEnvStatus,
    envProgress: AgentInstallProgress?,
    envInstalling: Boolean,
    pkgInstallingId: String?,
    onInstallEnv: () -> Unit,
    onRetryEnv: () -> Unit,
    onInstallPkg: (InstalledPlugin) -> Unit,
    onToggle: (String) -> Unit,
    onUninstall: (InstalledPlugin) -> Unit,
    onInstallLocal: () -> Unit,
    onSelect: (InstalledPlugin) -> Unit,
) {
    var installedCategory by remember { mutableStateOf(PluginCategories.ALL) }
    val filtered = installed.filter { plugin ->
        installedCategory == PluginCategories.ALL ||
            (plugin.info?.type ?: "") == installedCategory
    }
    // 需要 Node 环境的 DSH npm 类插件; 环境未就绪时在列表顶部提醒并提供一键补全
    val runtimeDependent = filtered.filter { !it.info?.npmPackages.isNullOrEmpty() }
    val envNotReady = envStatus != AgentEnvStatus.READY && envStatus != AgentEnvStatus.NO_ROOTFS
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (runtimeDependent.isNotEmpty() && envNotReady) {
            item(key = "env-banner") {
                WorkspaceEnvBanner(
                    dependentCount = runtimeDependent.size,
                    status = envStatus,
                    progress = envProgress,
                    installing = envInstalling,
                    onInstallEnv = onInstallEnv,
                    onRetryEnv = onRetryEnv,
                )
            }
        } else if (runtimeDependent.isNotEmpty()) {
            item(key = "env-ok") {
                Text(
                    text = "工作区环境已就绪，${runtimeDependent.size} 个插件的命令行能力可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "插件启用后注入系统提示并显示快捷操作",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onInstallLocal) {
                    Icon(HugeIcons.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("安装本地包", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        if (installed.isNotEmpty()) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(PluginCategories.marketTypes, key = { it }) { cat ->
                        FilterChip(
                            selected = installedCategory == cat,
                            onClick = { installedCategory = cat },
                            label = { Text(PluginCategories.typeLabel(cat)) },
                        )
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (installed.isEmpty()) "还没有安装插件" else "该分类下没有已安装插件",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(filtered, key = { it.id }) { plugin ->
            InstalledPluginCard(
                plugin = plugin,
                envNotReady = envNotReady,
                installingPkg = pkgInstallingId == plugin.id,
                onInstallPkg = { onInstallPkg(plugin) },
                onClick = { onSelect(plugin) },
                onToggle = { onToggle(plugin.id) },
                onUninstall = { onUninstall(plugin) },
            )
        }
    }
}

/**
 * 工作区环境提醒横幅: 存在需要 Node.js 的 DSH 插件且环境未就绪时展示。
 * 提供一键补全(检测→Node→常用工具), 失败时显示原因并可重试检测。
 */
@Composable
private fun WorkspaceEnvBanner(
    dependentCount: Int,
    status: AgentEnvStatus,
    progress: AgentInstallProgress?,
    installing: Boolean,
    onInstallEnv: () -> Unit,
    onRetryEnv: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    HugeIcons.InformationCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = when (status) {
                        AgentEnvStatus.NODE_MISSING -> "缺少 Node.js 运行环境"
                        AgentEnvStatus.TOOLS_MISSING -> "工作区工具不完整（git/curl 等）"
                        AgentEnvStatus.UNKNOWN, AgentEnvStatus.NO_ROOTFS -> "工作区环境未知"
                        else -> "工作区环境未就绪"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = "$dependentCount 个插件提供命令行能力，需要 Node.js 环境。补全后即可在对话中直接使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (installing && progress != null) {
                progress.percent?.let {
                    LinearProgressIndicator(
                        progress = { it / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = progress.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onInstallEnv,
                    enabled = !installing,
                ) {
                    if (installing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("安装中…", modifier = Modifier.padding(start = 6.dp))
                    } else {
                        Text("一键补全环境")
                    }
                }
                if (!installing && status == AgentEnvStatus.UNKNOWN) {
                    TextButton(onClick = onRetryEnv) { Text("重新检测") }
                }
            }
        }
    }
}

@Composable
internal fun InstalledPluginCard(
    plugin: InstalledPlugin,
    envNotReady: Boolean,
    installingPkg: Boolean,
    onInstallPkg: () -> Unit,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onUninstall: () -> Unit,
) {
    val info = plugin.info
    val needsRuntime = !info?.npmPackages.isNullOrEmpty()
    val hasCapability = info != null && (
        info.systemPrompt.isNotBlank() ||
            info.actions.isNotEmpty() ||
            info.extensionPoints.homeActions.isNotEmpty() ||
            info.extensionPoints.settingsActions.isNotEmpty() ||
            info.extensionPoints.sidebarActions.isNotEmpty() ||
            info.extensionPoints.chatToolbarActions.isNotEmpty() ||
            info.extensionPoints.inputBarActions.isNotEmpty()
        )
    val statusLabel = when {
        plugin.status == PluginStatus.BROKEN -> "损坏"
        plugin.status == PluginStatus.ENABLED && !hasCapability -> "资源包"
        plugin.status == PluginStatus.ENABLED -> "已生效"
        hasCapability -> "未生效"
        else -> "已安装"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = plugin.info?.name ?: plugin.id,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                    if (needsRuntime && envNotReady) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    "需 Node 环境",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
                if (plugin.info != null) {
                    if (plugin.info.description.isNotBlank()) {
                        Text(
                            text = plugin.info.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 本地结合: 声明了 npm CLI 的插件提供预装入口, 装完 npx 直接命中本地
                    if (needsRuntime) {
                        val pkgs = info.npmPackages.joinToString(", ")
                        Text(
                            text = "CLI: $pkgs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!envNotReady && plugin.status == PluginStatus.ENABLED) {
                            TextButton(
                                onClick = onInstallPkg,
                                enabled = !installingPkg,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                if (installingPkg) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                    Text("正在装入工作区…", modifier = Modifier.padding(start = 6.dp))
                                } else {
                                    Icon(HugeIcons.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Text("装入工作区", modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = "plugin.json 缺失或损坏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (plugin.status != PluginStatus.BROKEN) {
                Switch(checked = plugin.status == PluginStatus.ENABLED, onCheckedChange = { onToggle() })
            }
            IconButton(onClick = onUninstall) {
                Icon(
                    HugeIcons.Delete01,
                    contentDescription = "卸载",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
