package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.api.TavernListing
import me.rerere.rikkahub.data.model.Lorebook
import kotlin.uuid.Uuid

/**
 * 酒馆（SillyTavern）Tab：市场角色卡安装 + 本地卡/世界书/正则/预设导入。
 * 角色卡注册为本地助手；世界书注册 Lorebook；正则与预设应用到当前助手。
 */
@Composable
internal fun TavernMarketTab(
    entries: List<TavernListing>,
    loading: Boolean,
    error: String?,
    downloadingId: String?,
    importedKeys: Set<String>,
    lorebooks: List<Lorebook>,
    importing: Boolean,
    search: String,
    onSearchChange: (String) -> Unit,
    onInstall: (TavernListing) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onImportCardFile: () -> Unit,
    onImportWorldBook: () -> Unit,
    onImportRegex: () -> Unit,
    onApplyPreset: () -> Unit,
    onDeleteLorebook: (Uuid) -> Unit,
) {
    val filtered = remember(entries, search) {
        if (search.isBlank()) entries else entries.filter { entry ->
            entry.name.contains(search, ignoreCase = true) ||
                entry.description.contains(search, ignoreCase = true) ||
                entry.tags.any { it.contains(search, ignoreCase = true) }
        }
    }

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "import-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onImportCardFile, enabled = !importing) {
                            Text("角色卡 PNG/JSON")
                        }
                        OutlinedButton(onClick = onImportWorldBook) { Text("世界书") }
                        OutlinedButton(onClick = onImportRegex) { Text("正则脚本") }
                        OutlinedButton(onClick = onApplyPreset) { Text("预设参数") }
                    }
                    Text(
                        "本地酒馆文件直接导入：角色卡注册为助手（PNG 自动提取内嵌数据并用作背景），" +
                            "带世界书的卡片同步注册 Lorebook；正则与预设应用到当前选中助手。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (lorebooks.isNotEmpty()) {
                item(key = "lorebooks-header") {
                    Text("已注册的世界书 (${lorebooks.size})", style = MaterialTheme.typography.titleMedium)
                }
                items(lorebooks, key = { "lb-${it.id}" }) { book ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(book.name.ifEmpty { "未命名世界书" }, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${book.entries.size} 条目 · 助手详情→提示词注入 中关联",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDeleteLorebook(book.id) }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item(key = "market-header") {
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索市场角色卡（名称/描述/标签）") },
                    singleLine = true,
                )
            }

            when {
                loading && entries.isEmpty() -> item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }

                error != null && entries.isEmpty() -> item(key = "error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("重试") }
                        Text(
                            "也可以直接用上方按钮从本地导入任意酒馆文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                filtered.isEmpty() && entries.isNotEmpty() -> item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("没有匹配的角色卡", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }

                else -> items(filtered, key = { "tav-${it.id}" }) { entry ->
                    val downloading = downloadingId == "tavern-${entry.id}"
                    val installed = !downloading && (
                        importedKeys.any { it.substringBefore("@") == entry.name } ||
                        importedKeys.any { it.contains(entry.id) }
                    )
                    TavernEntryCard(
                        entry = entry,
                        installed = installed,
                        downloading = downloading,
                        onInstall = { onInstall(entry) },
                    )
                }
            }

            item(key = "footer-hint") {
                Text(
                    "市场条目来自插件仓库的 tavern.json 索引；社区海量角色卡可从酒馆生态下载后用上方「角色卡」按钮导入（兼容 V1/V2/V3 与 PNG 卡）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun TavernEntryCard(
    entry: TavernListing,
    installed: Boolean,
    downloading: Boolean,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                entry.emoji,
                modifier = Modifier.size(44.dp),
                style = MaterialTheme.typography.headlineMedium,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onInstall, enabled = !installed && !downloading) {
                        Text(
                            when {
                                downloading -> "下载中"
                                installed -> "已导入"
                                else -> when (entry.type) {
                                    "worldbook" -> "导入世界书"
                                    "preset" -> "应用预设"
                                    "regex" -> "导入正则"
                                    else -> "添加为助手"
                                }
                            }
                        )
                    }
                }
                if (entry.tags.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(entry.tags, key = { "$it-${entry.id}" }) { tag ->
                            AssistChip(onClick = {}, label = { Text(tag) })
                        }
                    }
                }
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
