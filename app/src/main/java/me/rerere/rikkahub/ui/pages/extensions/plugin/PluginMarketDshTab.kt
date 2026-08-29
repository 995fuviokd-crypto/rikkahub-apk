package me.rerere.rikkahub.ui.pages.extensions.plugin

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.Star
import me.rerere.rikkahub.data.api.DshCategory
import me.rerere.rikkahub.data.api.DshMarketDataSource
import me.rerere.rikkahub.data.api.DshMarketPlugin
import me.rerere.rikkahub.data.plugin.InstalledPlugin

/**
 * DSH（DeepSeek Harness）插件市场 Tab：
 * 实时 feed 列表 + 分类筛选 + star/下载量排序 + 一键安装（tarball 直链优先，回退仓库转换）。
 */
@Composable
internal fun DshMarketTab(
    entries: List<DshMarketPlugin>,
    installed: List<InstalledPlugin>,
    loading: Boolean,
    error: String?,
    categories: List<DshCategory>,
    updated: String,
    downloadingId: String?,
    search: String,
    onSearchChange: (String) -> Unit,
    onInstall: (DshMarketPlugin) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
) {
    val installedIds = remember(installed) { installed.map { it.id }.toSet() }
    var category by remember { mutableStateOf("all") }
    var sortByDownloads by remember { mutableStateOf(true) }

    val filtered = entries.filter { entry ->
        val matchSearch = search.isBlank() ||
            entry.name.contains(search, ignoreCase = true) ||
            entry.owner.contains(search, ignoreCase = true) ||
            entry.displayDescription.contains(search, ignoreCase = true)
        val matchCategory = category == "all" || entry.category == category
        matchSearch && matchCategory
    }.sortedWith(
        compareByDescending<DshMarketPlugin> { if (sortByDownloads) it.downloads else it.stars }
            .thenBy { it.name }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索 DSH 插件（名称/作者/描述）") },
            singleLine = true,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "all") {
                FilterChip(
                    selected = category == "all",
                    onClick = { category = "all" },
                    label = { Text("全部 (${entries.size})") },
                )
            }
            items(categories, key = { it.id }) { cat ->
                FilterChip(
                    selected = category == cat.id,
                    onClick = { category = cat.id },
                    label = { Text(cat.zh) },
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(listOf(true to "按下载量", false to "按 Star"), key = { it.second }) { (byDownloads, label) ->
                FilterChip(
                    selected = sortByDownloads == byDownloads,
                    onClick = { sortByDownloads = byDownloads },
                    label = { Text(label) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                loading && entries.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                error != null && entries.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重试") }
                }

                filtered.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("没有找到插件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (updated.isNotBlank()) {
                        item(key = "updated-at") {
                            Text(
                                text = "数据更新于 $updated · 共 ${entries.size} 个插件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(filtered, key = { "dsh-${it.owner}/${it.name}" }) { entry ->
                        DshEntryCard(
                            entry = entry,
                            installed = "dsh-${entry.repoRef.lowercase()
                                .replace(Regex("[^a-z0-9]+"), "-")
                                .trim('-')}" in installedIds,
                            downloading = downloadingId == "dsh-${entry.repoRef}",
                            onInstall = { onInstall(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DshEntryCard(
    entry: DshMarketPlugin,
    installed: Boolean,
    downloading: Boolean,
    onInstall: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            DshMarketDataSource.categoryLabel(entry.category),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                AssistChip(
                    onClick = {},
                    label = { Text("DSH", style = MaterialTheme.typography.labelSmall) },
                )
                if (entry.npm != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text("CLI", style = MaterialTheme.typography.labelSmall) },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = entry.owner,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = entry.displayDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(HugeIcons.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(
                        formatCount(entry.stars),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.downloads > 0) {
                    Text(
                        text = "${formatCount(entry.downloads)} 下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { runCatching { uriHandler.openUri(entry.url) } }) {
                    Icon(HugeIcons.Github, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text("仓库", modifier = Modifier.padding(start = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                when {
                    downloading -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    installed -> Text(
                        text = "已安装",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    else -> Button(onClick = onInstall) { Text("安装") }
                }
            }
        }
    }
}

/** 数字缩写：>=10000 显示 x.x万 */
internal fun formatCount(value: Int): String = when {
    value >= 10000 -> String.format("%.1f万", value / 10000.0).removeSuffix(".0万") + "万"
    value > 0 -> value.toString()
    else -> "0"
}
