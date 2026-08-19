package me.rerere.rikkahub.ui.pages.extensions.permission

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CommandLine
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.LaptopCheck
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.data.permission.AuditEntry
import me.rerere.rikkahub.data.permission.PermissionLevel
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.pages.extensions.group.formatTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun PermissionPage(vm: PermissionVM = koinViewModel()) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val level by vm.level.collectAsStateWithLifecycle()
    val accessibilityReady by vm.accessibilityReady.collectAsStateWithLifecycle()
    val adbReady by vm.adbReady.collectAsStateWithLifecycle()
    val rootReady by vm.rootReady.collectAsStateWithLifecycle()
    val shizukuReady by vm.shizukuReady.collectAsStateWithLifecycle()
    val shizukuLoaded by vm.shizukuLoaded.collectAsStateWithLifecycle()
    val headlessEnabled by vm.headlessEnabled.collectAsStateWithLifecycle()
    val headlessSupport by vm.headlessSupport.collectAsStateWithLifecycle()
    val auditLogs by vm.auditLogs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("权限管理") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh, contentDescription = "刷新")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "当前权限层级：${level.label()}",
                    style = MaterialTheme.typography.titleSmall,
                    color = level.color(),
                )
            }

            item {
                PermissionCard(
                    icon = { Icon(HugeIcons.Eye, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                    title = "无障碍权限",
                    description = "读取界面节点与执行手势，支持 UI 自动化（点击/滑动/导航/打开应用）",
                    ready = accessibilityReady,
                    readyText = "已开启",
                    action = {
                        if (!accessibilityReady) {
                            Button(onClick = { openAccessibilitySettings(context) }) {
                                Text("去开启")
                            }
                        }
                    },
                )
            }

            item {
                PermissionCard(
                    icon = { Icon(HugeIcons.CommandLine, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                    title = "ADB 权限",
                    description = "shell 命令、输入注入与启动应用；无 root 设备通过 Shizuku 激活",
                    ready = adbReady,
                    readyText = if (adbReady) {
                        if (rootReady) "已通过 Root 激活" else "Shizuku 已授权"
                    } else {
                        "未激活"
                    },
                    action = {
                        if (!adbReady) {
                            if (shizukuLoaded) {
                                Button(onClick = { vm.requestShizuku() }) {
                                    Text(if (shizukuReady) "已授权" else "请求 Shizuku 授权")
                                }
                            } else {
                                OutlinedButton(onClick = { openShizukuApp(context) }) {
                                    Text("安装并启动 Shizuku")
                                }
                            }
                        }
                    },
                )
            }

            item {
                PermissionCard(
                    icon = { Icon(HugeIcons.Shield01, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                    title = "Root 权限",
                    description = "最高级系统权限（su），支持完整虚拟显示与系统级操作",
                    ready = rootReady,
                    readyText = if (rootReady) "su 可用" else "不可用",
                    action = {
                        if (!rootReady) {
                            Text(
                                text = "未检测到 root 环境，无 root 设备请使用 Shizuku 方案",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                HugeIcons.LaptopCheck,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text("虚拟屏幕", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = headlessSupport,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = headlessEnabled && adbReady,
                                onCheckedChange = {
                                    vm.setHeadless(it)
                                    if (it && !adbReady) {
                                        vm.refresh()
                                    }
                                },
                                enabled = adbReady,
                            )
                        }
                        if (!adbReady) {
                            Text(
                                text = "需要 ADB 或 Root 权限才能启用无头后台自动化",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("审计日志", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (auditLogs.isNotEmpty()) {
                        TextButton(onClick = { vm.clearAudit() }) {
                            Text("清空")
                        }
                    }
                }
            }

            if (auditLogs.isEmpty()) {
                item {
                    Text(
                        text = "暂无审计记录。高权限操作（命令/输入/启动应用）执行后将记录在此。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(auditLogs, key = { "${it.time}-${it.action}" }) { entry ->
                AuditRow(entry)
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    ready: Boolean,
    readyText: String,
    action: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = readyText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ready) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action()
        }
    }
}

@Composable
private fun AuditRow(entry: AuditEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.level,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = entry.action,
                    style = MaterialTheme.typography.labelMedium,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(entry.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openShizukuApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } else {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("https://shizuku.rikka.app/")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}

fun PermissionLevel.label(): String = when (this) {
    PermissionLevel.NONE -> "未授权"
    PermissionLevel.ACCESSIBILITY -> "无障碍"
    PermissionLevel.ADB -> "ADB"
    PermissionLevel.ROOT -> "Root"
}

fun PermissionLevel.color(): androidx.compose.ui.graphics.Color = when (this) {
    PermissionLevel.NONE -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
    PermissionLevel.ACCESSIBILITY -> androidx.compose.ui.graphics.Color(0xFFFF6D00)
    PermissionLevel.ADB -> androidx.compose.ui.graphics.Color(0xFF2962FF)
    PermissionLevel.ROOT -> androidx.compose.ui.graphics.Color(0xFF00C853)
}
