package me.rerere.rikkahub.ui.pages.extensions.plugin

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import me.rerere.rikkahub.Screen
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.Menu01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.plugin.PluginMarketEntry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** 插件市场主页面：已安装 / 市场（官方+社区合并）/ DSH / 酒馆 四个 Tab */
@Composable
fun PluginMarketPage(vm: PluginMarketVM = koinViewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pluginManager: PluginManager = koinInject()
    val navController = me.rerere.rikkahub.ui.context.LocalNavController.current
    val installed by vm.installed.collectAsStateWithLifecycle()
    val runtimeLoaded by vm.runtimeLoaded.collectAsStateWithLifecycle()
    val marketEntries by vm.marketEntries.collectAsStateWithLifecycle()
    val marketLoading by vm.marketLoading.collectAsStateWithLifecycle()
    val marketError by vm.marketError.collectAsStateWithLifecycle()
    val downloadingId by vm.downloadingId.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val githubToken by vm.githubToken.collectAsStateWithLifecycle()
    val marketRepo by vm.marketRepo.collectAsStateWithLifecycle()
    val communityEntries by vm.communityEntries.collectAsStateWithLifecycle()
    val communityLoading by vm.communityLoading.collectAsStateWithLifecycle()
    val communityError by vm.communityError.collectAsStateWithLifecycle()
    val communityInstallingId by vm.communityInstallingId.collectAsStateWithLifecycle()
    val dshEntries by vm.dshEntries.collectAsStateWithLifecycle()
    val dshLoading by vm.dshLoading.collectAsStateWithLifecycle()
    val dshError by vm.dshError.collectAsStateWithLifecycle()
    val dshCategories by vm.dshCategories.collectAsStateWithLifecycle()
    val dshUpdated by vm.dshUpdated.collectAsStateWithLifecycle()

    // 酒馆（角色卡/世界书/正则/预设）
    val tavernEntries by vm.tavernEntries.collectAsStateWithLifecycle()
    val tavernLoading by vm.tavernLoading.collectAsStateWithLifecycle()
    val tavernError by vm.tavernError.collectAsStateWithLifecycle()
    val tavernImportedKeys by vm.tavernImportedKeys.collectAsStateWithLifecycle()
    val tavernLorebooks by vm.tavernLorebooks.collectAsStateWithLifecycle()
    val tavernImporting by vm.tavernImporting.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(PluginCategories.ALL) }
    var showMenu by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var showTutorialDialog by remember { mutableStateOf(false) }
    var showOpenAIDialog by remember { mutableStateOf(false) }
    var showDshDialog by remember { mutableStateOf(false) }
    var uploadType by remember { mutableStateOf(PluginCategories.TYPE_PLUGIN) }
    var deleteTarget by remember { mutableStateOf<InstalledPlugin?>(null) }
    var selectedEntry by remember { mutableStateOf<PluginMarketEntry?>(null) }
    var selectedInstalled by remember { mutableStateOf<InstalledPlugin?>(null) }

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

    // 酒馆角色卡导入（PNG 内嵌卡或 JSON 卡）
    val tavernCardLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }?.let { bytes ->
                val mime = context.contentResolver.getType(uri) ?: ""
                val name = uri.lastPathSegment ?: ""
                val isPng = mime == "image/png" || name.endsWith(".png", ignoreCase = true)
                vm.importTavernCardFromBytes(bytes, name, isPng)
            }
        }
    }

    // 酒馆世界书 JSON 导入
    val tavernWorldBookLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().use { it.readText() }
            }?.let { text ->
                vm.importWorldInfo(text, it.lastPathSegment)
            }
        }
    }

    // 酒馆正则脚本 JSON 导入（应用到当前助手）
    val tavernRegexLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().use { r -> r.readText() }
            }?.let { text -> vm.importRegexScripts(text) }
        }
    }

    // 酒馆预设 JSON 应用到当前助手
    val tavernPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().use { r -> r.readText() }
            }?.let { text -> vm.applyPreset(text) }
        }
    }

    // 选择本地插件 zip 上传/导出
    val uploadZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val token = vm.githubToken.value
                if (token.isBlank()) {
                    // 未配置 Token：不强制必填，导出 zip 到本地并分享
                    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = File(exportDir, "rikkahub-plugin-${System.currentTimeMillis()}.zip")
                    file.writeBytes(bytes)
                    val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "导出插件包"))
                } else {
                    vm.upload(bytes, uploadType) {}
                }
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

    // 工作区环境状态: DSH npm 类插件的环境提醒与一键补全
    val envStatus by vm.envStatus.collectAsStateWithLifecycle()
    val envProgress by vm.envProgress.collectAsStateWithLifecycle()
    val envInstalling by vm.envInstalling.collectAsStateWithLifecycle()
    val pkgInstallingId by vm.pkgInstallingId.collectAsStateWithLifecycle()
    val pkgNotice by vm.pkgNotice.collectAsStateWithLifecycle()

    LaunchedEffect(pkgNotice) {
        val message = pkgNotice
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            vm.consumePkgNotice()
        }
    }

    // 进入市场页自动拉取最新索引；已安装 Tab 静默拉市场索引用于"可更新"徽章
    LaunchedEffect(tab) {
        when (tab) {
            0 -> {
                if (installed.isNotEmpty() && marketEntries.isEmpty()) {
                    vm.loadMarket()
                    vm.loadCommunity()
                }
            }
            1 -> {
                vm.loadMarket()
                vm.loadCommunity()
            }
            2 -> vm.loadDshMarket()
            3 -> vm.loadTavernMarket()
        }
    }

    // 运行时信息探测（面板入口/内核资格）：随已安装列表重算
    val runtimeInfo = remember(installed, pluginManager) {
        installed.associate { plugin ->
            val dir = pluginManager.getPluginDir(plugin.id)
            val scriptDir = me.rerere.rikkahub.data.script.ScriptRuntime.scriptDir(dir)
            val hasScript = scriptDir.isDirectory && scriptDir.listFiles().orEmpty().any { it.extension == "js" }
            // 统一面板探测：显式声明优先（schema 轨 panel.json / web 轨 entry），缺省特征探测 web 轨
            val panelSpec = pluginManager.resolvePanelSpec(plugin.id, plugin.info)
            val panelFile = panelSpec
                ?.takeIf { it.type == me.rerere.rikkahub.data.plugin.PluginPanelSpec.TYPE_WEB }
                ?.let { pluginManager.resolveWebResourceFile(plugin.id, it.entry) }
            plugin.id to PluginRuntimeInfo(
                kernelEligible = panelSpec?.type == me.rerere.rikkahub.data.plugin.PluginPanelSpec.TYPE_WEB || hasScript,
                panelFile = panelFile,
                panelSpec = panelSpec,
            )
        }
    }

    // 已安装插件可更新版本（官方 + 社区市场聚合）
    val updateVersions = remember(installed, marketEntries, communityEntries) {
        vm.updateVersionsFor(installed)
    }

    // 打开插件面板：schema 轨路由原生渲染器，web 轨路由插件 WebView 宿主
    val openPluginPanel: (InstalledPlugin) -> Unit = { plugin ->
        val spec = runtimeInfo[plugin.id]?.panelSpec
        when (spec?.type) {
            me.rerere.rikkahub.data.plugin.PluginPanelSpec.TYPE_SCHEMA ->
                navController.navigate(Screen.SchemaPanel(pluginId = plugin.id))

            else -> runtimeInfo[plugin.id]?.panelFile?.let { file ->
                navController.navigate(Screen.WebView(url = file.toURI().toString(), contentId = "", pluginId = plugin.id))
            }
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
                                text = { Text("导入 DSH 插件") },
                                leadingIcon = {
                                    Icon(HugeIcons.Github, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    showDshDialog = true
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
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text("DSH 市场") },
                )
                Tab(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    text = { Text("酒馆") },
                )
            }

            when (tab) {
                0 -> InstalledTab(
                    installed = installed,
                    runtimeLoaded = runtimeLoaded,
                    runtimeInfo = runtimeInfo,
                    updateVersions = updateVersions,
                    envStatus = envStatus,
                    envProgress = envProgress,
                    envInstalling = envInstalling,
                    pkgInstallingId = pkgInstallingId,
                    onInstallEnv = vm::installWorkspaceEnv,
                    onRetryEnv = vm::refreshEnvStatus,
                    onInstallPkg = { vm.installPkgToWorkspace(it) },
                    onToggle = vm::toggleEnabled,
                    onInstallLocal = { localZipLauncher.launch(arrayOf("application/zip", "*/*")) },
                    onSelect = { selectedInstalled = it },
                    onOpenPanel = openPluginPanel,
                    onGoMarket = { tab = 1 },
                )

                1 -> MarketTab(
                    entries = marketEntries,
                    communityEntries = communityEntries,
                    installed = installed,
                    loading = marketLoading,
                    error = marketError,
                    communityLoading = communityLoading,
                    communityError = communityError,
                    downloadingId = downloadingId,
                    communityInstallingId = communityInstallingId,
                    search = search,
                    onSearchChange = { search = it },
                    category = category,
                    onCategoryChange = { category = it },
                    onInstall = vm::install,
                    onInstallCommunity = vm::installCommunity,
                    onSelect = { selectedEntry = it },
                    onRetryMarket = vm::loadMarket,
                    onRetryCommunity = vm::loadCommunity,
                    communityUpdateFor = vm::communityUpdateFor,
                    officialUpdateFor = vm::officialUpdateFor,
                )

                2 -> DshMarketTab(
                    entries = dshEntries,
                    installed = installed,
                    loading = dshLoading,
                    error = dshError,
                    categories = dshCategories,
                    updated = dshUpdated,
                    downloadingId = downloadingId,
                    search = search,
                    onSearchChange = { search = it },
                    onInstall = vm::installDshMarketEntry,
                    onRetry = vm::loadDshMarket,
                    onRefresh = vm::loadDshMarket,
                )

                3 -> TavernMarketTab(
                    entries = tavernEntries,
                    loading = tavernLoading,
                    error = tavernError,
                    downloadingId = downloadingId,
                    importedKeys = tavernImportedKeys,
                    lorebooks = tavernLorebooks,
                    importing = tavernImporting,
                    search = search,
                    onSearchChange = { search = it },
                    onInstall = vm::installTavernEntry,
                    onRetry = vm::loadTavernMarket,
                    onRefresh = vm::loadTavernMarket,
                    onImportCardFile = { tavernCardLauncher.launch(arrayOf("image/png", "application/json", "*/*")) },
                    onImportWorldBook = { tavernWorldBookLauncher.launch(arrayOf("application/json", "*/*")) },
                    onImportRegex = { tavernRegexLauncher.launch(arrayOf("application/json", "*/*")) },
                    onApplyPreset = { tavernPresetLauncher.launch(arrayOf("application/json", "*/*")) },
                    onDeleteLorebook = vm::removeLorebook,
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
            onPickFile = { uploadZipLauncher.launch(arrayOf("application/zip", "*/*")) },
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

    if (showDshDialog) {
        DshImportDialog(
            installing = downloadingId == "dsh",
            onImport = vm::installDsh,
            onDismiss = { showDshDialog = false },
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

    selectedInstalled?.let { plugin ->
        InstalledPluginDetailDialog(
            plugin = plugin,
            installDir = pluginManager.getPluginDir(plugin.id).absolutePath,
            onToggle = { vm.toggleEnabled(plugin.id) },
            onUninstall = {
                deleteTarget = plugin
                selectedInstalled = null
            },
            onDismiss = { selectedInstalled = null },
            onOpenPanel = runtimeInfo[plugin.id]?.panelSpec?.let { { openPluginPanel(plugin) } },
            updateVersion = updateVersions[plugin.id],
        )
    }
}
