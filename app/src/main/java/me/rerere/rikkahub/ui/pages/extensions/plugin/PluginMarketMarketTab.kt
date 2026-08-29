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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.Download03
import me.rerere.hugeicons.stroke.Fire
import me.rerere.hugeicons.stroke.Star
import me.rerere.rikkahub.data.api.CommunityListItem
import me.rerere.rikkahub.data.api.CommunityMarketDataSource
import me.rerere.rikkahub.data.api.communityPluginIdFor
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginMarketEntry
import me.rerere.rikkahub.ui.theme.CustomColors

private const val MARKET_SOURCE_ALL = "全部"
private const val MARKET_SOURCE_OFFICIAL = "官方市场"
private const val MARKET_SOURCE_COMMUNITY = "社区市场"

/** 社区市场资源类型映射到市场分类（script/package 视为插件） */
internal fun communityCategoryFor(type: String): String = when (type) {
    "skill" -> PluginCategories.TYPE_SKILL
    "mcp" -> PluginCategories.TYPE_MCP
    "script", "package" -> PluginCategories.TYPE_PLUGIN
    else -> type
}

/** 官方 + 社区市场合并 Tab：统一搜索/分类/来源筛选，现代卡片展示下载量与精选标识 */
@Composable
internal fun MarketTab(
    entries: List<PluginMarketEntry>,
    communityEntries: List<CommunityListItem>,
    installed: List<InstalledPlugin>,
    loading: Boolean,
    error: String?,
    communityLoading: Boolean,
    communityError: String?,
    downloadingId: String?,
    communityInstallingId: String?,
    search: String,
    onSearchChange: (String) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
    onInstall: (PluginMarketEntry) -> Unit,
    onInstallCommunity: (CommunityListItem) -> Unit,
    onSelect: (PluginMarketEntry) -> Unit,
    onRetryMarket: () -> Unit,
    onRetryCommunity: () -> Unit,
    communityUpdateFor: (CommunityListItem, List<InstalledPlugin>) -> String?,
    officialUpdateFor: (PluginMarketEntry, List<InstalledPlugin>) -> String?,
) {
    val installedIds = remember(installed) { installed.map { it.id }.toSet() }
    var source by remember { mutableStateOf(MARKET_SOURCE_ALL) }
    val sourceOptions = listOf(MARKET_SOURCE_ALL, MARKET_SOURCE_OFFICIAL, MARKET_SOURCE_COMMUNITY)
    val categories = PluginCategories.marketTypes

    val filteredOfficial = entries.filter { entry ->
        val matchSearch = search.isBlank() ||
            entry.name.contains(search, ignoreCase = true) ||
            entry.description.contains(search, ignoreCase = true) ||
            entry.id.contains(search, ignoreCase = true)
        val matchCategory = category == PluginCategories.ALL || entry.type == category
        matchSearch && matchCategory
    }
    val filteredCommunity = communityEntries.filter { entry ->
        val matchSearch = search.isBlank() ||
            entry.title.contains(search, ignoreCase = true) ||
            entry.description.contains(search, ignoreCase = true)
        val matchCategory = category == PluginCategories.ALL || communityCategoryFor(entry.type) == category
        matchSearch && matchCategory
    }
    val showOfficial = source != MARKET_SOURCE_COMMUNITY
    val showCommunity = source != MARKET_SOURCE_OFFICIAL
    val listEmpty =
        (showOfficial && filteredOfficial.isEmpty()) && (showCommunity && filteredCommunity.isEmpty())

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索插件（官方 + 社区）") },
            singleLine = true,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sourceOptions, key = { it }) { option ->
                FilterChip(
                    selected = source == option,
                    onClick = { source = option },
                    label = { Text(option) },
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories, key = { it }) { cat ->
                FilterChip(
                    selected = category == cat,
                    onClick = { onCategoryChange(cat) },
                    label = { Text(PluginCategories.typeLabel(cat)) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = loading || communityLoading,
            onRefresh = {
                if (source != MARKET_SOURCE_COMMUNITY) onRetryMarket()
                if (source != MARKET_SOURCE_OFFICIAL) onRetryCommunity()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                loading && communityLoading && entries.isEmpty() && communityEntries.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                error != null && communityError != null && entries.isEmpty() && communityEntries.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetryMarket) { Text("重试") }
                }

                listEmpty -> Box(
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
                    if (showOfficial && filteredOfficial.isNotEmpty()) {
                        item(key = "official-header") {
                            MarketSectionHeader(
                                title = "官方市场",
                                count = filteredOfficial.size,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(filteredOfficial, key = { "official-${it.id}" }) { entry ->
                            MarketEntryCard(
                                entry = entry,
                                installed = entry.id in installedIds,
                                downloading = downloadingId == entry.id,
                                availableUpdate = officialUpdateFor(entry, installed),
                                onClick = { onSelect(entry) },
                                onInstall = { onInstall(entry) },
                            )
                        }
                    } else if (showOfficial && error != null) {
                        item {
                            Text(
                                "官方市场加载失败：$error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRetryMarket) { Text("重试") }
                        }
                    }
                    if (showCommunity && filteredCommunity.isNotEmpty()) {
                        item(key = "community-header") {
                            MarketSectionHeader(
                                title = "社区市场",
                                count = filteredCommunity.size,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        items(filteredCommunity, key = { "community-${it.id}" }) { entry ->
                            CommunityEntryCard(
                                entry = entry,
                                installing = communityInstallingId == entry.id,
                                installed = communityPluginIdFor(entry.id).let { pid ->
                                    pid in installedIds || pid.replaceFirst("community-", "operit-") in installedIds
                                },
                                availableUpdate = communityUpdateFor(entry, installed),
                                onInstall = { onInstallCommunity(entry) },
                            )
                        }
                    } else if (showCommunity && communityError != null) {
                        item {
                            Text(
                                "社区市场加载失败：$communityError",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRetryCommunity) { Text("重试") }
                        }
                    }
                }
            }
        }
    }
}

/** 市场区块标题（来源 + 数量） */
@Composable
private fun MarketSectionHeader(title: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = color)
        Text(
            text = " $count",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 社区市场条目卡片：展示下载量/精选标识/作者/版本等完整市场字段 */
@Composable
internal fun CommunityEntryCard(
    entry: CommunityListItem,
    installing: Boolean,
    installed: Boolean,
    availableUpdate: String?,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(CommunityMarketDataSource.typeLabel(entry.type)) },
                )
                AssistChip(
                    onClick = {},
                    label = { Text("社区", style = MaterialTheme.typography.labelSmall) },
                )
                if (entry.featured) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(HugeIcons.Fire, contentDescription = null, modifier = Modifier.size(12.dp))
                                Text("精选", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 2.dp))
                            }
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = entry.displayAuthor,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.sourceKind == "github_repo" && entry.source?.repoName.isNullOrBlank().not()) {
                Text(
                    text = "来源: ${entry.source?.repoOwner.orEmpty()}/${entry.source?.repoName.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val downloads = entry.displayDownloads
                if (downloads > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(HugeIcons.Download03, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(
                            text = " ${formatCount(downloads)} 下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (entry.latestVersion.version.isNotBlank()) {
                    Text(
                        text = " · v${entry.latestVersion.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.weight(1f))
                when {
                    installing -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    availableUpdate != null -> Button(onClick = onInstall) { Text("更新 $availableUpdate") }
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

/** 官方市场条目卡片 */
@Composable
internal fun MarketEntryCard(
    entry: PluginMarketEntry,
    installed: Boolean,
    downloading: Boolean,
    availableUpdate: String?,
    onClick: () -> Unit,
    onInstall: () -> Unit,
) {
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
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                if (entry.description.isNotBlank()) {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.author.isNotBlank()) {
                    Text(
                        text = "作者：${entry.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                downloading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                availableUpdate != null -> TextButton(onClick = onInstall) { Text("更新 $availableUpdate") }
                installed -> Text(
                    text = "已安装",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                else -> TextButton(onClick = onInstall) { Text("安装") }
            }
        }
    }
}
