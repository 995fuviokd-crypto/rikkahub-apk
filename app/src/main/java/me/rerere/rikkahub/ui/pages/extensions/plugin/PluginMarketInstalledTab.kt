package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.data.ai.agent.AgentEnvStatus
import me.rerere.rikkahub.data.ai.agent.AgentInstallProgress
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginCapabilityPreflight
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginStatus
import me.rerere.rikkahub.ui.theme.CustomColors
/** 已安装插件的运行时信息（页面级 File 探测，避免卡片重组期反复 IO） */
data class PluginRuntimeInfo(
    /** 面板/脚本插件：启用后会加载进 Cordis 内核（运行状态有意义） */
    val kernelEligible: Boolean,
    /** web/index.html 存在：可提供"打开面板"直达入口 */
    val panelFile: java.io.File?,
    /** 可用面板规格（统一探测：web 轨 entry HTML 或 schema 轨 panel.json） */
    val panelSpec: me.rerere.rikkahub.data.plugin.PluginPanelSpec? = null,
)

/** 卡片运行状态模型（区别于安装态：反映内核真实加载结果） */
internal enum class PluginRunState(val label: String) {
    RUNNING("运行中"),
    LOAD_FAILED("加载失败"),
    EFFECTIVE("已生效"),
    STOPPED("已停用"),
    RESOURCE_PACK("资源包"),
    BROKEN("已损坏"),
    INSTALLED("已安装"),
}

internal fun pluginRunState(
    plugin: InstalledPlugin,
    runtimeInfo: PluginRuntimeInfo?,
    runtimeLoaded: Set<String>,
): PluginRunState {
    val hasCapability = plugin.info != null && (
        plugin.info.systemPrompt.isNotBlank() ||
            plugin.info.actions.isNotEmpty() ||
            plugin.info.extensionPoints.homeActions.isNotEmpty() ||
            plugin.info.extensionPoints.settingsActions.isNotEmpty() ||
            plugin.info.extensionPoints.sidebarActions.isNotEmpty() ||
            plugin.info.extensionPoints.chatToolbarActions.isNotEmpty() ||
            plugin.info.extensionPoints.inputBarActions.isNotEmpty()
        )
    return when {
        plugin.status == PluginStatus.BROKEN -> PluginRunState.BROKEN
        plugin.status == PluginStatus.ENABLED && !hasCapability -> PluginRunState.RESOURCE_PACK
        // 面板/脚本插件启用但内核未加载：真实失败态（apply 崩溃/依赖缺失），不再静默显示"已生效"
        plugin.status == PluginStatus.ENABLED &&
            runtimeInfo?.kernelEligible == true &&
            plugin.id !in runtimeLoaded -> PluginRunState.LOAD_FAILED
        plugin.status == PluginStatus.ENABLED && runtimeInfo?.kernelEligible == true -> PluginRunState.RUNNING
        plugin.status == PluginStatus.ENABLED -> PluginRunState.EFFECTIVE
        hasCapability -> PluginRunState.STOPPED
        else -> PluginRunState.INSTALLED
    }
}

/** 已安装插件 Tab：运行状态披露 + 能力徽章 + 更新提示 + 面板直达 + 分类筛选 */
@Composable
internal fun InstalledTab(
    installed: List<InstalledPlugin>,
    runtimeLoaded: Set<String>,
    runtimeInfo: Map<String, PluginRuntimeInfo>,
    updateVersions: Map<String, String>,
    envStatus: AgentEnvStatus,
    envProgress: AgentInstallProgress?,
    envInstalling: Boolean,
    pkgInstallingId: String?,
    onInstallEnv: () -> Unit,
    onRetryEnv: () -> Unit,
    onInstallPkg: (InstalledPlugin) -> Unit,
    onToggle: (String) -> Unit,
    onInstallLocal: () -> Unit,
    onSelect: (InstalledPlugin) -> Unit,
    onOpenPanel: (InstalledPlugin) -> Unit,
    onGoMarket: () -> Unit,
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
            item(key = "empty") {
                EmptyStateGuide(
                    hasAny = installed.isNotEmpty(),
                    onGoMarket = onGoMarket,
                )
            }
        }
        items(filtered, key = { it.id }) { plugin ->
            InstalledPluginCard(
                plugin = plugin,
                runtimeInfo = runtimeInfo[plugin.id],
                runState = pluginRunState(plugin, runtimeInfo[plugin.id], runtimeLoaded),
                updateVersion = updateVersions[plugin.id],
                envNotReady = envNotReady,
                installingPkg = pkgInstallingId == plugin.id,
                onInstallPkg = { onInstallPkg(plugin) },
                onClick = { onSelect(plugin) },
                onToggle = { onToggle(plugin.id) },
                onOpenPanel = { runtimeInfo[plugin.id]?.panelSpec?.let { onOpenPanel(plugin) } },
            )
        }
    }
}

/** 空态引导：装了插件的分类过滤空态与全空态分开，全空态给"去市场逛逛"直达 */
@Composable
private fun EmptyStateGuide(
    hasAny: Boolean,
    onGoMarket: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Puzzle,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (hasAny) "该分类下没有已安装插件" else "还没有安装插件",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasAny) {
            Text(
                text = "从市场安装插件，为对话扩展提示词、工具与面板",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onGoMarket) {
                Icon(HugeIcons.Search01, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("去市场逛逛", modifier = Modifier.padding(start = 4.dp))
            }
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

/** 运行状态小徽章：色点 + 文字（替代原先无语义的 AssistChip 假按钮） */
@Composable
private fun RunStateBadge(state: PluginRunState) {
    val (color, label) = when (state) {
        PluginRunState.RUNNING -> MaterialTheme.colorScheme.primary to state.label
        PluginRunState.LOAD_FAILED, PluginRunState.BROKEN -> MaterialTheme.colorScheme.error to state.label
        PluginRunState.EFFECTIVE -> MaterialTheme.colorScheme.primary to state.label
        else -> MaterialTheme.colorScheme.onSurfaceVariant to state.label
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = color, shape = CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
internal fun InstalledPluginCard(
    plugin: InstalledPlugin,
    runtimeInfo: PluginRuntimeInfo?,
    runState: PluginRunState,
    updateVersion: String?,
    envNotReady: Boolean,
    installingPkg: Boolean,
    onInstallPkg: () -> Unit,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onOpenPanel: () -> Unit,
) {
    val info = plugin.info
    val needsRuntime = !info?.npmPackages.isNullOrEmpty()
    val requestedCaps = remember(info?.id, info?.tags) {
        info?.let { PluginCapabilityPreflight.requestedFromTags(it.tags) }.orEmpty()
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = info?.name ?: plugin.id,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!info?.version.isNullOrBlank()) {
                        Text(
                            text = "v${info!!.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RunStateBadge(runState)
                    if (updateVersion != null) {
                        Text(
                            text = "可更新 v$updateVersion",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (needsRuntime && envNotReady) {
                        Text(
                            text = "需 Node 环境",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (info != null) {
                    if (info.description.isNotBlank()) {
                        Text(
                            text = info.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 能力徽章：披露插件申请的宿主能力（信任模型入口，点击进详情看完整清单）
                    if (requestedCaps.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            requestedCaps.take(3).forEach { cap ->
                                Text(
                                    text = cap,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier
                                        .background(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = MaterialTheme.shapes.extraSmall,
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            if (requestedCaps.size > 3) {
                                Text(
                                    text = "+${requestedCaps.size - 3}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
            // 面板插件直达入口：装了面板就该一键打开，不再让用户找入口
            if (runtimeInfo?.panelSpec != null) {
                IconButton(onClick = onOpenPanel) {
                    Icon(
                        HugeIcons.Puzzle,
                        contentDescription = "打开面板",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (plugin.status != PluginStatus.BROKEN) {
                Switch(checked = plugin.status == PluginStatus.ENABLED, onCheckedChange = { onToggle() })
            }
        }
    }
}
