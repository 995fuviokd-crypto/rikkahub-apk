package me.rerere.rikkahub.ui.pages.extensions.plugin

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginMarketEntry
import me.rerere.rikkahub.data.plugin.PluginStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import java.io.ByteArrayOutputStream

@Composable
fun PluginMarketPage(vm: PluginMarketVM = koinViewModel()) {
    val context = LocalContext.current
    val installed by vm.installed.collectAsStateWithLifecycle()
    val marketEntries by vm.marketEntries.collectAsStateWithLifecycle()
    val marketLoading by vm.marketLoading.collectAsStateWithLifecycle()
    val marketError by vm.marketError.collectAsStateWithLifecycle()
    val downloadingId by vm.downloadingId.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val githubToken by vm.githubToken.collectAsStateWithLifecycle()
    val marketRepo by vm.marketRepo.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(PluginCategories.ALL) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var showRepoDialog by remember { mutableStateOf(false) }
    var showTutorialDialog by remember { mutableStateOf(false) }
    var showOpenAIDialog by remember { mutableStateOf(false) }
    var uploadType by remember { mutableStateOf(PluginCategories.TYPE_PLUGIN) }
    var deleteTarget by remember { mutableStateOf<InstalledPlugin?>(null) }
    var uploadResult by remember { mutableStateOf<String?>(null) }

    // 选择本地插件 zip 安装
    val localZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                vm.installFromZip(bytes)
            }
        }
    }

    // 选择本地插件 zip 上传
    val uploadZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                vm.upload(bytes, uploadType) { url ->
                    uploadResult = url
                }
            }
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2500)
            vm.clearNotice()
        }
    }

    // 进入市场页自动拉取最新索引（实时同步）
    LaunchedEffect(tab) {
        if (tab == 1) {
            vm.loadMarket()
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("插件") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showTutorialDialog = true }) {
                        Icon(HugeIcons.BookOpen01, contentDescription = "制作教程")
                    }
                    IconButton(onClick = { showRepoDialog = true }) {
                        Icon(HugeIcons.Settings03, contentDescription = "市场设置")
                    }
                    IconButton(onClick = { showUploadDialog = true }) {
                        Icon(HugeIcons.CloudUpload, contentDescription = "上传插件")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("已安装 (${installed.size})") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("市场") },
                )
            }

            when (tab) {
                0 -> InstalledTab(
                    installed = installed,
                    onToggle = vm::toggleEnabled,
                    onUninstall = { deleteTarget = it },
                    onInstallLocal = { localZipLauncher.launch(arrayOf("application/zip", "*/*")) },
                    onImportOpenAI = { showOpenAIDialog = true },
                )

                1 -> MarketTab(
                    entries = marketEntries,
                    installed = installed,
                    loading = marketLoading,
                    error = marketError,
                    downloadingId = downloadingId,
                    search = search,
                    onSearchChange = { search = it },
                    category = category,
                    onCategoryChange = { category = it },
                    onInstall = vm::install,
                    onRetry = vm::loadMarket,
                    onRefresh = vm::loadMarket,
                )
            }
        }
    }

    notice?.let {
        RikkaNotice(text = it, onDismiss = vm::clearNotice)
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = "卸载插件",
        confirmText = "卸载",
        dismissText = "取消",
        onConfirm = {
            deleteTarget?.let { vm.uninstall(it.id) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text("确定要卸载「${deleteTarget?.info?.name ?: deleteTarget?.id}」吗？")
    }

    if (showUploadDialog) {
        UploadDialog(
            token = githubToken,
            repo = marketRepo,
            selectedType = uploadType,
            onTypeChange = { uploadType = it },
            onTokenChange = vm::setGithubToken,
            onRepoChange = vm::setMarketRepo,
            onPickFile = { uploadZipLauncher.launch(arrayOf("application/zip", "*/*")) },
            onShowTutorial = { showTutorialDialog = true },
            onDismiss = { showUploadDialog = false },
        )
    }

    if (showTutorialDialog) {
        PluginTutorialDialog(onDismiss = { showTutorialDialog = false })
    }

    if (showOpenAIDialog) {
        OpenAIImportDialog(
            installing = downloadingId == "openai",
            onImport = vm::installOpenAIPlugin,
            onDismiss = { showOpenAIDialog = false },
        )
    }

    if (showRepoDialog) {
        RepoDialog(
            repo = marketRepo,
            onRepoChange = vm::setMarketRepo,
            onDismiss = { showRepoDialog = false },
        )
    }

    uploadResult?.let { url ->
        UploadSuccessDialog(
            url = url,
            onDismiss = { uploadResult = null },
        )
    }
}

@Composable
private fun InstalledTab(
    installed: List<InstalledPlugin>,
    onToggle: (String) -> Unit,
    onUninstall: (InstalledPlugin) -> Unit,
    onInstallLocal: () -> Unit,
    onImportOpenAI: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                TextButton(onClick = onImportOpenAI) {
                    Icon(HugeIcons.Puzzle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("导入 OpenAI", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = onInstallLocal) {
                    Icon(HugeIcons.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("安装本地包", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        if (installed.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("还没有安装插件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(installed, key = { it.id }) { plugin ->
            InstalledPluginCard(
                plugin = plugin,
                onToggle = { onToggle(plugin.id) },
                onUninstall = { onUninstall(plugin) },
            )
        }
    }
}

@Composable
private fun InstalledPluginCard(
    plugin: InstalledPlugin,
    onToggle: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                                when (plugin.status) {
                                    PluginStatus.ENABLED -> "已生效"
                                    PluginStatus.INSTALLED -> "未生效"
                                    PluginStatus.BROKEN -> "损坏"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                if (plugin.info != null) {
                    Text(
                        text = "v${plugin.info.version} · ${plugin.info.category}${if (plugin.info.author.isNotBlank()) " · ${plugin.info.author}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (plugin.info.description.isNotBlank()) {
                        Text(
                            text = plugin.info.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (plugin.info.actions.isNotEmpty()) {
                        Text(
                            text = "快捷操作: ${plugin.info.actions.joinToString("、") { it.label }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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

@Composable
private fun MarketTab(
    entries: List<PluginMarketEntry>,
    installed: List<InstalledPlugin>,
    loading: Boolean,
    error: String?,
    downloadingId: String?,
    search: String,
    onSearchChange: (String) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
    onInstall: (PluginMarketEntry) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
) {
    val installedIds = remember(installed) { installed.map { it.id }.toSet() }
    val categories = PluginCategories.known
    val filtered = entries.filter { entry ->
        val matchSearch = search.isBlank() ||
            entry.name.contains(search, ignoreCase = true) ||
            entry.description.contains(search, ignoreCase = true) ||
            entry.id.contains(search, ignoreCase = true) ||
            entry.tags.any { it.contains(search, ignoreCase = true) }
        val matchCategory = category == PluginCategories.ALL ||
            entry.type == category ||
            entry.category == category ||
            entry.tags.contains(category)
        matchSearch && matchCategory
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索插件 / 技能 / MCP / 标签") },
            singleLine = true,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories, key = { it }) { cat ->
                FilterChip(
                    selected = category == cat,
                    onClick = { onCategoryChange(cat) },
                    label = { Text(cat) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            } else if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("没有找到插件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        MarketEntryCard(
                            entry = entry,
                            installed = entry.id in installedIds,
                            downloading = downloadingId == entry.id,
                            onInstall = { onInstall(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketEntryCard(
    entry: PluginMarketEntry,
    installed: Boolean,
    downloading: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                }
                Text(
                    text = "v${entry.version}${if (entry.author.isNotBlank()) " · ${entry.author}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.description.isNotBlank()) {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val extraTags = (entry.tags + listOf(entry.category).filter { it.isNotBlank() && it != "general" && it != entry.type })
                    .distinct()
                    .take(4)
                if (extraTags.isNotEmpty()) {
                    Text(
                        text = extraTags.joinToString(" #", prefix = "#"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                downloading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
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

@Composable
private fun UploadDialog(
    token: String,
    repo: String,
    selectedType: String,
    onTypeChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onRepoChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onShowTutorial: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tokenInput by remember { mutableStateOf(token) }
    var repoInput by remember { mutableStateOf(repo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上传插件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "使用 GitHub 个人访问令牌（PAT，需 contents:write 权限）把插件上传到你的仓库 plugins/ 目录并更新索引。支持插件、技能、MCP 配置等多种资源包。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    value = repoInput,
                    onValueChange = { repoInput = it },
                    label = { Text("目标仓库 owner/repo") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("GitHub Token (PAT)") },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        onRepoChange(repoInput)
                        onTokenChange(tokenInput)
                        onPickFile()
                        onDismiss()
                    },
                ) {
                    Icon(HugeIcons.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("选择文件并上传", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = onShowTutorial) {
                    Icon(HugeIcons.BookOpen01, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("查看制作教程", modifier = Modifier.padding(start = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun RepoDialog(
    repo: String,
    onRepoChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(repo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("市场索引仓库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "索引仓库根目录需包含 plugins.json（插件列表），插件 zip 放在 plugins/ 目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("owner/repo") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onRepoChange(input)
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun UploadSuccessDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上传成功") },
        text = {
            Text(
                text = url,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun RikkaNotice(
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提示") },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        },
    )
}

@Composable
private fun OpenAIImportDialog(
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun PluginTutorialDialog(
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
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                Text("打包步骤", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. 新建目录，放入 plugin.json 及附属文件\n" +
                        "2. 将目录内容压缩为 zip（zip 根目录需直接含 plugin.json）\n" +
                        "3. 在「已安装」页选择「安装本地包」，或用「上传」分享到市场",
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
