package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Menu01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginMarketEntry
import me.rerere.rikkahub.data.plugin.PluginStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun PluginMarketPage(vm: PluginMarketVM = koinViewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
    var showMenu by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var showRepoDialog by remember { mutableStateOf(false) }
    var showTutorialDialog by remember { mutableStateOf(false) }
    var showOpenAIDialog by remember { mutableStateOf(false) }
    var uploadType by remember { mutableStateOf(PluginCategories.TYPE_PLUGIN) }
    var deleteTarget by remember { mutableStateOf<InstalledPlugin?>(null) }
    var selectedEntry by remember { mutableStateOf<PluginMarketEntry?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // 选择本地插件 zip 安装
    val localZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(uri)?.use { input ->
                vm.installFromZip(input.readBytes())
            }
        }
    }

    // 选择本地插件 zip 上传
    val uploadZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(uri)?.use { input ->
                vm.upload(input.readBytes(), uploadType) {}
            }
        }
    }

    // 操作结果以 Snackbar 提示
    LaunchedEffect(notice) {
        val message = notice
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            vm.clearNotice()
        }
    }

    // 进入市场页自动拉取最新索引
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
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(HugeIcons.Menu01, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("提交插件") },
                                leadingIcon = {
                                    Icon(HugeIcons.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    showUploadDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导入 OpenAI 插件") },
                                leadingIcon = {
                                    Icon(HugeIcons.Puzzle, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    showOpenAIDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("市场仓库") },
                                leadingIcon = {
                                    Icon(HugeIcons.Settings03, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    showRepoDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("制作教程") },
                                leadingIcon = {
                                    Icon(HugeIcons.BookOpen01, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    showTutorialDialog = true
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    onSelect = { selectedEntry = it },
                    onRetry = vm::loadMarket,
                    onRefresh = vm::loadMarket,
                    onEditRepo = { showRepoDialog = true },
                )
            }
        }
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
            onDismiss = { showUploadDialog = false },
        )
    }

    if (showRepoDialog) {
        RepoDialog(
            repo = marketRepo,
            onRepoChange = vm::setMarketRepo,
            onDismiss = { showRepoDialog = false },
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

    selectedEntry?.let { entry ->
        val installedIds = installed.map { it.id }.toSet()
        PluginDetailDialog(
            entry = entry,
            installed = entry.id in installedIds,
            downloading = downloadingId == entry.id,
            onInstall = {
                vm.install(entry)
                selectedEntry = null
            },
            onDismiss = { selectedEntry = null },
        )
    }
}

@Composable
private fun InstalledTab(
    installed: List<InstalledPlugin>,
    onToggle: (String) -> Unit,
    onUninstall: (InstalledPlugin) -> Unit,
    onInstallLocal: () -> Unit,
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
                    if (plugin.info.description.isNotBlank()) {
                        Text(
                            text = plugin.info.description,
                            style = MaterialTheme.typography.bodySmall,
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
    onSelect: (PluginMarketEntry) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onEditRepo: () -> Unit,
) {
    val installedIds = remember(installed) { installed.map { it.id }.toSet() }
    val categories = PluginCategories.marketTypes
    val filtered = entries.filter { entry ->
        val matchSearch = search.isBlank() ||
            entry.name.contains(search, ignoreCase = true) ||
            entry.description.contains(search, ignoreCase = true) ||
            entry.id.contains(search, ignoreCase = true)
        val matchCategory = category == PluginCategories.ALL || entry.type == category
        matchSearch && matchCategory
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索插件") },
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
                    label = { Text(PluginCategories.typeLabel(cat)) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                error != null -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRetry) { Text("重试") }
                        TextButton(onClick = onEditRepo) { Text("修改仓库") }
                    }
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
                    items(filtered, key = { it.id }) { entry ->
                        MarketEntryCard(
                            entry = entry,
                            installed = entry.id in installedIds,
                            downloading = downloadingId == entry.id,
                            onClick = { onSelect(entry) },
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
                }
                if (entry.description.isNotBlank()) {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
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
private fun PluginDetailDialog(
    entry: PluginMarketEntry,
    installed: Boolean,
    downloading: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
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
                        text = "GitHub 仓库",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { uriHandler.openUri(repoUrl) },
                    )
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
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (installed) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                TextButton(onClick = onInstall) { Text("安装") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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
    onDismiss: () -> Unit,
) {
    var tokenInput by remember { mutableStateOf(token) }
    var repoInput by remember { mutableStateOf(repo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提交插件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "使用 GitHub Token（PAT，需 contents:write 权限）将插件提交到市场的待审核队列，管理员审核通过后才会在市场上架。",
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
                    Text("选择文件并提交", modifier = Modifier.padding(start = 4.dp))
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
