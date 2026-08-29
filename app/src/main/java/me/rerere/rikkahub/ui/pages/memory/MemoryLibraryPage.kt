package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download02
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryTarget
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import java.io.File

@Composable
fun MemoryLibraryPage(vm: MemoryLibraryVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val selectedTarget by vm.selectedTarget.collectAsStateWithLifecycle()
    val memories by vm.filtered.collectAsStateWithLifecycle()
    val importResult by vm.importResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取所选文件")
                }.getOrNull()
                if (text != null) vm.importJson(text)
            }
        }
    }

    LaunchedEffect(importResult) {
        if (importResult != null) {
            snackbarHostState.showSnackbar(importResult.orEmpty())
            vm.consumeImportResult()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_memory)) },
                navigationIcon = { BackButton() },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(HugeIcons.MoreVertical, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("导出 JSON") },
                                leadingIcon = { Icon(HugeIcons.Download02, null) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        val file = File(context.cacheDir, "memories_export.json")
                                        file.writeText(vm.exportJson())
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file,
                                        )
                                        context.startActivity(
                                            android.content.Intent.createChooser(
                                                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                    type = "application/json"
                                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                },
                                                "导出记忆 JSON",
                                            ),
                                        )
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导入 JSON") },
                                leadingIcon = { Icon(HugeIcons.Upload02, null) },
                                onClick = {
                                    menuExpanded = false
                                    importLauncher.launch("application/json")
                                },
                            )
                        }
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
                MemorySummaryCard(
                    total = memories.size,
                    globalCount = vm.filtered.value.count { it.assistantId == me.rerere.rikkahub.data.repository.MemoryRepository.GLOBAL_MEMORY_ID },
                )
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { vm.searchQuery.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.memory_search_hint)) },
                    leadingIcon = { Icon(HugeIcons.Search01, null, modifier = Modifier.size(20.dp)) },
                    singleLine = true,
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedTarget == null,
                        onClick = { vm.selectedTarget.value = null },
                        label = { Text(stringResource(R.string.memory_filter_all)) },
                    )
                    vm.targets().forEach { target ->
                        FilterChip(
                            selected = selectedTarget == target.name,
                            onClick = { vm.selectedTarget.value = target.name },
                            label = { Text(targetLabel(target)) },
                        )
                    }
                }
            }

            if (memories.isEmpty()) {
                item {
                    Text(
                        text = "暂无记忆。开启助手的记忆功能并对话后，系统会在这里沉淀长期记忆。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            items(memories, key = { it.id }) { memory ->
                MemoryLibraryItem(
                    memory = memory,
                    onDelete = { vm.delete(memory) },
                )
            }
        }
    }
}

@Composable
private fun MemorySummaryCard(total: Int, globalCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Sparkles,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "全局记忆库",
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = "共 $total 条记忆 · 全局共享 $globalCount 条 · 跨所有助手沉淀",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MemoryLibraryItem(
    memory: AssistantMemory,
    onDelete: () -> Unit,
) {
    val target = rememberMemoryTarget(memory)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = targetColor(target).copy(alpha = 0.15f),
                        contentColor = targetColor(target),
                    ) {
                        Text(
                            text = targetLabel(target),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (memory.assistantId == me.rerere.rikkahub.data.repository.MemoryRepository.GLOBAL_MEMORY_ID) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                text = "全局",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = " #${memory.id}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                memory.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(
                        text = summary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = memory.content,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = formatTimestamp(memory.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.assistant_page_delete),
                )
            }
        }
    }
}

@Composable
private fun rememberMemoryTarget(memory: AssistantMemory): MemoryTarget =
    androidx.compose.runtime.remember(memory.target) { MemoryTarget.fromString(memory.target) }

@Composable
private fun formatTimestamp(updatedAt: Long): String {
    val time = androidx.compose.runtime.remember(updatedAt) { java.time.Instant.ofEpochMilli(updatedAt) }
    return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(time)
}

@Composable
private fun targetLabel(target: MemoryTarget): String = when (target) {
    MemoryTarget.USER -> stringResource(R.string.memory_target_user)
    MemoryTarget.MEMORY -> stringResource(R.string.memory_target_memory)
    MemoryTarget.PROJECT -> stringResource(R.string.memory_target_project)
    MemoryTarget.OPS -> stringResource(R.string.memory_target_ops)
    MemoryTarget.GENERAL -> stringResource(R.string.memory_target_general)
}

@Composable
private fun targetColor(target: MemoryTarget) = when (target) {
    MemoryTarget.USER -> MaterialTheme.colorScheme.primary
    MemoryTarget.MEMORY -> MaterialTheme.colorScheme.tertiary
    MemoryTarget.PROJECT -> MaterialTheme.colorScheme.secondary
    MemoryTarget.OPS -> MaterialTheme.colorScheme.error
    MemoryTarget.GENERAL -> MaterialTheme.colorScheme.outline
}