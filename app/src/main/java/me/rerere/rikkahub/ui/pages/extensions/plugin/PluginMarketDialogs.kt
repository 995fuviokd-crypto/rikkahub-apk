package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginMarketEntry

/** 官方市场条目详情对话框 */
@Composable
internal fun PluginDetailDialog(
    entry: PluginMarketEntry,
    installed: Boolean,
    downloading: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // R7.4 能力预检门控：申请能力宿主全未实现时禁装（下面 preflight 块内赋值）
    var installBlocked by remember(entry.tags) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {},
                        label = { Text(PluginCategories.typeLabel(entry.type), style = MaterialTheme.typography.labelSmall) },
                    )
                    if (entry.version.isNotBlank()) {
                        AssistChip(
                            onClick = {},
                            label = { Text("v${entry.version}", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (entry.description.isNotBlank()) {
                    Text(entry.description, style = MaterialTheme.typography.bodyMedium)
                }
                if (entry.author.isNotBlank()) {
                    Text(
                        text = "作者：${entry.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val repoUrl = entry.repository
                if (repoUrl.isNotBlank() && (repoUrl.startsWith("https://") || repoUrl.startsWith("http://"))) {
                    Text(
                        text = repoUrl,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        maxLines = 1,
                        modifier = Modifier.clickable { uriHandler.openUri(repoUrl) },
                    )
                }
                // 安装前能力（权限）披露：声明的能力缝逐项列出，宿主未实现项标灰
                val preflight = remember(entry.tags) {
                    me.rerere.rikkahub.data.plugin.PluginCapabilityPreflight.check(
                        me.rerere.rikkahub.data.plugin.PluginCapabilityPreflight.requestedFromTags(entry.tags),
                        me.rerere.rikkahub.data.plugin.CordisRuntimeHost.HOST_CAPABILITIES,
                    )
                }
                if (preflight.requested.isNotEmpty()) {
                    Text("申请能力", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        preflight.requested.take(6).forEach { cap ->
                            val supported = cap in preflight.supported
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
                    if (!preflight.allSupported) {
                        Text(
                            text = "灰色能力在当前宿主暂未实现，安装后调用会返回不可用。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 申请能力宿主一个都不支持 → 不可装（R7.4 预检门控）
                    installBlocked = preflight.requested.isNotEmpty() && preflight.supported.isEmpty()
                }
                if (entry.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        entry.tags.take(4).forEach { tag ->
                            AssistChip(
                                onClick = {},
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (downloading) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("安装中…", style = MaterialTheme.typography.labelMedium)
                }
            } else if (installed) {
                TextButton(onClick = onDismiss) { Text("已安装，点击关闭") }
            } else if (installBlocked) {
                // R7.4 "已证明可装"才亮安装按钮：申请能力宿主全未实现 → 禁装并说明理由
                Text(
                    text = "无法安装",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = onInstall) { Text("安装") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 提交插件对话框：选择类型 + 可选 GitHub Token（不填则仅本地导出） */
@Composable
internal fun UploadDialog(
    token: String,
    repo: String,
    selectedType: String,
    onTypeChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tokenInput by remember { mutableStateOf(token) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提交插件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "将插件 zip 提交到官方市场（$repo）的待审核队列，管理员审核通过后上架。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("类型", style = MaterialTheme.typography.bodySmall)
                    PluginCategories.types.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { onTypeChange(type) },
                            label = { Text(PluginCategories.typeLabel(type)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("GitHub Token (PAT，可选)") },
                    supportingText = {
                        Text(
                            if (tokenInput.isBlank()) "不填则仅本地导出插件包，不提交。" else "Token 将持久化保存，下次无需再填。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        onTokenChange(tokenInput)
                        onPickFile()
                        onDismiss()
                    },
                ) {
                    Icon(HugeIcons.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("选择文件", modifier = Modifier.padding(start = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 导入 OpenAI 兼容插件对话框 */
@Composable
internal fun OpenAIImportDialog(
    installing: Boolean,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 OpenAI 插件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "填写 OpenAI 兼容插件仓库地址（域名或 GitHub owner/repo）。App 会自动读取 /.well-known/ai-plugin.json 并转换为可安装的插件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("地址，如 example.com 或 owner/repo") },
                    singleLine = true,
                    enabled = !installing,
                )
                if (installing) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text("正在获取并安装...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onImport(url)
                    onDismiss()
                },
                enabled = url.isNotBlank() && !installing,
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 导入 DSH 插件对话框 */
@Composable
internal fun DshImportDialog(
    installing: Boolean,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 DSH 插件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "填写 DeepSeek Harness（DSH）插件仓库地址。App 会自动拉取仓库并提取可迁移能力：技能资源转为技能、工具定义转为能力提示词；纯 UI / Node 宿主依赖的插件无法迁移。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("仓库地址，如 github:owner/repo 或 owner/repo") },
                    singleLine = true,
                    enabled = !installing,
                )
                if (installing) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text("正在获取并转换...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onImport(url)
                    onDismiss()
                },
                enabled = url.isNotBlank() && !installing,
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 插件制作教程对话框 */
@Composable
internal fun PluginTutorialDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插件制作教程") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "插件以 zip 包分发，包根目录必须包含 plugin.json。支持插件、技能、MCP 配置等多种资源类型。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("plugin.json 示例", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "{ \"id\": \"my-plugin\",\n  \"name\": \"我的插件\",\n  \"version\": \"1.0.0\",\n  \"description\": \"插件描述\",\n  \"author\": \"作者\",\n  \"category\": \"productivity\",\n  \"type\": \"plugin\",\n  \"systemPrompt\": \"启用后注入的系统提示\",\n  \"tags\": [\"翻译\", \"写作\"],\n  \"actions\": [\n    { \"label\": \"翻译\", \"prompt\": \"请翻译这段内容：\" }\n  ],\n  \"extensionPoints\": {\n    \"settingsActions\": [\n      { \"id\": \"s1\", \"label\": \"打开帮助\", \"target\": \"url\", \"payload\": \"https://example.com\" }\n    ],\n    \"homeActions\": []\n  } }",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text("打包步骤", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. 新建目录，放入 plugin.json 及附属文件\n" +
                        "2. 将目录内容压缩为 zip（zip 根目录需直接含 plugin.json）\n" +
                        "3. 在「已安装」页选择「安装本地包」，或用「提交插件」分享到市场（审核通过后上架）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("资源类型", style = MaterialTheme.typography.titleSmall)
                Text(
                    PluginCategories.types.joinToString(" / ") { PluginCategories.typeLabel(it) },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "zip 内无 plugin.json 时，按所选类型登记为资源包（技能/MCP 配置等）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("扩展能力（extensionPoints）", style = MaterialTheme.typography.titleSmall)
                Text(
                    "target 取值：prompt（填入输入框）、url（打开链接）、copy（复制文本）。\n" +
                        "启用插件后 settingsActions 显示在设置页，homeActions 显示在主界面，无需修改 App 代码。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}
