package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.agent.AcpEnvironmentManager
import me.rerere.rikkahub.data.ai.agent.AgentEnvStatus
import me.rerere.rikkahub.data.ai.agent.AgentInstallPhase
import me.rerere.rikkahub.data.ai.agent.AgentInstallProgress
import me.rerere.rikkahub.data.api.CommunityListItem
import me.rerere.rikkahub.data.api.CommunityMarketDataSource
import me.rerere.rikkahub.data.api.DshMarketDataSource
import me.rerere.rikkahub.data.api.DshMarketPlugin
import me.rerere.rikkahub.data.api.PluginMarketDataSource
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.plugin.DshPluginAdapter
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.OpenAIPluginAdapter
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginInfo
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.plugin.PluginMarketEntry
import me.rerere.rikkahub.data.plugin.PluginStatus
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.api.TavernListing
import me.rerere.rikkahub.data.api.TavernMarketDataSource
import me.rerere.rikkahub.data.api.communityPluginIdFor
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.tavern.TavernCardConverter
import me.rerere.rikkahub.data.tavern.TavernCard
import me.rerere.rikkahub.data.tavern.TavernPng
import kotlin.uuid.Uuid

class PluginMarketVM(
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
    private val marketDataSource: PluginMarketDataSource,
    private val openAIPluginAdapter: OpenAIPluginAdapter,
    private val communityDataSource: CommunityMarketDataSource,
    private val dshPluginAdapter: DshPluginAdapter,
    private val dshMarketDataSource: DshMarketDataSource,
    private val tavernMarketDataSource: TavernMarketDataSource,
    private val environmentManager: AcpEnvironmentManager,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val installMutex = Mutex()

    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed = _installed.asStateFlow()

    /** 工作区插件运行环境(Node.js + 常用工具)的就绪状态, 供 DSH npm 类插件的环境提醒 */
    private val _envStatus = MutableStateFlow(AgentEnvStatus.UNKNOWN)
    val envStatus = _envStatus.asStateFlow()

    /** 一键补全环境的进度; null 表示空闲 */
    private val _envProgress = MutableStateFlow<AgentInstallProgress?>(null)
    val envProgress = _envProgress.asStateFlow()

    private val _envInstalling = MutableStateFlow(false)
    val envInstalling = _envInstalling.asStateFlow()

    /** 正在预装到工作区的 npm 包(取 PluginInfo.id), null 表示空闲 */
    private val _pkgInstallingId = MutableStateFlow<String?>(null)
    val pkgInstallingId = _pkgInstallingId.asStateFlow()

    private val _pkgNotice = MutableStateFlow<String?>(null)
    val pkgNotice = _pkgNotice.asStateFlow()

    private val _marketEntries = MutableStateFlow<List<PluginMarketEntry>>(emptyList())
    val marketEntries = _marketEntries.asStateFlow()

    private val _marketLoading = MutableStateFlow(false)
    val marketLoading = _marketLoading.asStateFlow()

    private val _marketError = MutableStateFlow<String?>(null)
    val marketError = _marketError.asStateFlow()

    private val _downloadingId = MutableStateFlow<String?>(null)
    val downloadingId = _downloadingId.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    private val _githubToken = MutableStateFlow("")
    val githubToken = _githubToken.asStateFlow()

    private val _marketRepo = MutableStateFlow(Settings.DEFAULT_PLUGIN_MARKET_REPO)
    val marketRepo = _marketRepo.asStateFlow()

    val enabledPlugins: Set<String> get() = _enabledPlugins
    private var _enabledPlugins: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            _enabledPlugins = settings.enabledPlugins
            _githubToken.value = settings.githubToken
            _marketRepo.value = settings.pluginMarketRepo
            // 内置「插件包制作技能」：仅随 App 预置（出现在已安装列表），不自动启用。
            // 启用后才注入其 systemPrompt，按需使用，避免污染原 AI。
            pluginManager.ensureBuiltinSkill()
            // 一次性清理旧版本自动启用的残留：v2.4.12 之前首次打开插件页会自动启用该技能，
            // 升级后把内置 skill 从 enabledPlugins 移除，此后用户手动启用不受影响。
            if (!settings.builtinMakerSkillCleanupDone) {
                val cleaned = _enabledPlugins - PluginManager.BUILTIN_PLUGIN_MAKER_ID
                if (cleaned != _enabledPlugins) {
                    _enabledPlugins = cleaned
                    settingsStore.update { it.copy(enabledPlugins = cleaned) }
                }
                settingsStore.update { it.copy(builtinMakerSkillCleanupDone = true) }
            }
            refreshInstalled()
            loadMarket()
            refreshEnvStatus()
        }
        // 持续同步启用集合：助手详情/技能页/AI 工具等其他入口修改后，市场页状态保持一致
        viewModelScope.launch {
            settingsStore.settingsFlow
                .map { it.enabledPlugins }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled != _enabledPlugins) {
                        _enabledPlugins = enabled
                        refreshInstalled()
                    }
                }
        }
    }

    fun refreshInstalled() {
        val enabled = _enabledPlugins
        _installed.value = pluginManager.listPlugins().map { plugin ->
            if (plugin.info != null && plugin.id in enabled) {
                plugin.copy(status = PluginStatus.ENABLED)
            } else {
                plugin
            }
        }
    }

    private suspend fun firstWorkspaceRoot(): String? =
        workspaceRepository.listFlow().first().firstOrNull()?.root

    /** 重新检测工作区运行环境状态 */
    fun refreshEnvStatus() {
        viewModelScope.launch {
            val root = firstWorkspaceRoot() ?: return@launch
            _envStatus.value = environmentManager.checkRuntime(root)
        }
    }

    /**
     * 一键补全工作区环境(Node.js + 常用工具), 完成后刷新状态。
     * 进行中重复调用被忽略; 失败详情保留在 envProgress(FAILED 阶段)中展示。
     */
    fun installWorkspaceEnv() {
        if (_envInstalling.value) return
        viewModelScope.launch {
            val root = firstWorkspaceRoot() ?: return@launch
            _envInstalling.value = true
            _envProgress.value = AgentInstallProgress(AgentInstallPhase.CHECKING, "准备中…")
            environmentManager.ensureRuntimeWithProgress(root) { progress ->
                _envProgress.value = progress
            }.onSuccess {
                _envStatus.value = AgentEnvStatus.READY
            }.onFailure {
                // 失败时重查真实状态: 可能 node 已装好只差 tools
                _envStatus.value = environmentManager.checkRuntime(root)
            }
            _envInstalling.value = false
        }
    }

    /**
     * 将插件的 npm CLI 包预装到工作区(全局安装)。装完后 AI 在终端用 npx 调用时
     * 直接命中本地包, 免联网解析。结果经 pkgNotice 一次性反馈给 UI。
     */
    fun installPkgToWorkspace(plugin: InstalledPlugin) {
        val pkgs = plugin.info?.npmPackages.orEmpty()
        if (pkgs.isEmpty()) return
        if (_pkgInstallingId.value != null) return
        viewModelScope.launch {
            val root = firstWorkspaceRoot() ?: return@launch
            _pkgInstallingId.value = plugin.id
            runCatching {
                pkgs.forEach { pkg ->
                    environmentManager.installGlobalPackage(root, pkg).getOrThrow()
                }
            }.onSuccess {
                _pkgNotice.value = "已安装到工作区：${pkgs.joinToString()}（终端中可直接使用）"
            }.onFailure {
                _pkgNotice.value = "工作区安装失败：${it.message}"
            }
            _pkgInstallingId.value = null
        }
    }

    /** 消费一次性提示(Snackbar 等) */
    fun consumePkgNotice() {
        _pkgNotice.value = null
    }

    /**
     * 社区市场更新检测：已安装 community-* 插件的 version 与市场 latestVersion 对比，
     * 仅当市场版本更高时提示更新。
     */
    fun communityUpdateFor(entry: CommunityListItem, installed: List<InstalledPlugin>): String? {
        val marketVersion = entry.latestVersion.version.trim().takeIf { it.isNotEmpty() } ?: return null
        val pid = communityPluginIdFor(entry.id)
        val current = installed.firstOrNull {
            it.id == pid || it.id == pid.replaceFirst("community-", "operit-")
        } ?: return null
        val localVersion = current.info?.version?.trim().orEmpty()
        return if (localVersion.isNotEmpty() && isNewerVersion(marketVersion, localVersion)) {
            marketVersion
        } else {
            null
        }
    }

    /** 简单的语义版本比较：marketVersion 高于 localVersion 时返回 true */
    private fun isNewerVersion(marketVersion: String, localVersion: String): Boolean {
        if (marketVersion.equals(localVersion, ignoreCase = true)) return false
        // 剥离前导 v/V 后按数字段逐段比较（"v2.0.0" 应高于 "1.5.0"）
        val marketParts = marketVersion.trim().removePrefix("v").removePrefix("V")
            .split(Regex("[._-]")).mapNotNull { it.toIntOrNull() }
        val localParts = localVersion.trim().removePrefix("v").removePrefix("V")
            .split(Regex("[._-]")).mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(marketParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val m = marketParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (m != l) return m > l
        }
        return false
    }

    /**
     * 官方市场更新检测：已安装插件的 version 与市场条目 version 对比，
     * 仅当市场版本更高时提示更新。
     */
    fun officialUpdateFor(entry: PluginMarketEntry, installed: List<InstalledPlugin>): String? {
        val marketVersion = entry.version.trim().takeIf { it.isNotEmpty() } ?: return null
        val current = installed.firstOrNull { it.id == entry.id } ?: return null
        val localVersion = current.info?.version?.trim().orEmpty()
        return if (localVersion.isNotEmpty() && isNewerVersion(marketVersion, localVersion)) {
            marketVersion
        } else {
            null
        }
    }

    fun loadMarket() {
        viewModelScope.launch {
            _marketLoading.value = true
            _marketError.value = null
            val result = marketDataSource.parseIndex(_marketRepo.value)
            result.onSuccess {
                _marketEntries.value = it
            }.onFailure {
                _marketError.value = "市场加载失败: ${it.message}"
            }
            _marketLoading.value = false
        }
    }

    fun setMarketRepo(repo: String) {
        _marketRepo.value = repo.trim().trim('/')
        viewModelScope.launch {
            settingsStore.update { it.copy(pluginMarketRepo = _marketRepo.value) }
        }
    }

    // ---- 社区市场市场 ----
    private val _communityEntries = MutableStateFlow<List<CommunityListItem>>(emptyList())
    val communityEntries = _communityEntries.asStateFlow()

    private val _communityLoading = MutableStateFlow(false)
    val communityLoading = _communityLoading.asStateFlow()

    private val _communityError = MutableStateFlow<String?>(null)
    val communityError = _communityError.asStateFlow()

    private val _communitySort = MutableStateFlow("likes")
    val communitySort = _communitySort.asStateFlow()

    private val _communityType = MutableStateFlow("all")
    val communityType = _communityType.asStateFlow()

    private val _communityInstallingId = MutableStateFlow<String?>(null)
    val communityInstallingId = _communityInstallingId.asStateFlow()

    fun loadCommunity() {
        viewModelScope.launch {
            _communityLoading.value = true
            _communityError.value = null
            communityDataSource.fetchList(_communityType.value, _communitySort.value, 1)
                .onSuccess { _communityEntries.value = it.items }
                .onFailure { _communityError.value = "社区市场加载失败: ${it.message}" }
            _communityLoading.value = false
        }
    }

    fun setCommunityType(type: String) {
        _communityType.value = type
        loadCommunity()
    }

    fun setCommunitySort(sort: String) {
        _communitySort.value = sort
        loadCommunity()
    }

    /** 安装社区市场条目：GitHub 目录打包为插件 zip，经 autoAdapt 自动适配后本地生效 */
    fun installCommunity(entry: CommunityListItem) {
        viewModelScope.launch {
            installMutex.withLock {
                if (_communityInstallingId.value != null) return@withLock
                _communityInstallingId.value = entry.id
            }
            _notice.value = null
            communityDataSource.downloadAsPlugin(entry)
                .onSuccess { bytes ->
                    pluginManager.installZip(bytes)
                        .onSuccess { info ->
                            autoEnablePlugin(info.id)
                            _notice.value = "已安装并启用 ${info.name}（社区市场）"
                        }
                        .onFailure { _notice.value = "安装失败: ${it.message}" }
                }
                .onFailure { _notice.value = "下载失败: ${it.message}" }
            _communityInstallingId.value = null
            refreshInstalled()
        }
    }

    fun setGithubToken(token: String) {
        _githubToken.value = token.trim()
        viewModelScope.launch {
            settingsStore.update { it.copy(githubToken = _githubToken.value) }
        }
    }

    fun toggleEnabled(pluginId: String) {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            val enabled = if (pluginId in settings.enabledPlugins) {
                settings.enabledPlugins - pluginId
            } else {
                settings.enabledPlugins + pluginId
            }
            _enabledPlugins = enabled
            settingsStore.update { it.copy(enabledPlugins = enabled) }
            if (pluginId in enabled) {
                registerMcpServersIfNeeded(pluginId)
            } else {
                unregisterMcpServers(pluginId)
            }
            refreshInstalled()
        }
    }

    fun install(entry: PluginMarketEntry) {
        if (entry.downloadUrl.isBlank()) {
            _notice.value = "安装失败：该条目缺少下载地址"
            return
        }
        viewModelScope.launch {
            installMutex.withLock {
                if (_downloadingId.value != null) return@withLock
                _downloadingId.value = entry.id
            }
            _notice.value = null
            try {
                val bytes = withContext(Dispatchers.IO) {
                    marketDataSource.downloadZip(entry.downloadUrl)
                }
                pluginManager.installZip(bytes)
                    .onSuccess { info ->
                        autoEnablePlugin(info.id)
                        _notice.value = "已安装并启用 ${info.name} ${info.version}"
                    }
                    .onFailure { _notice.value = "安装失败: ${it.message}" }
            } catch (e: Throwable) {
                _notice.value = "下载失败: ${e.message}"
            }
            _downloadingId.value = null
            refreshInstalled()
        }
    }

    /** 从本地 zip 字节安装（SAF 选择文件） */
    fun installFromZip(bytes: ByteArray) {
        viewModelScope.launch {
            _notice.value = null
            pluginManager.installZip(bytes)
                .onSuccess { info ->
                    autoEnablePlugin(info.id)
                    _notice.value = "已安装并启用 ${info.name} ${info.version}"
                }
                .onFailure { _notice.value = "安装失败: ${it.message}" }
            refreshInstalled()
        }
    }

    /** 从 OpenAI 兼容插件仓库地址安装（读取 /.well-known/ai-plugin.json 自动转换） */
    fun installOpenAIPlugin(url: String) {
        viewModelScope.launch {
            installMutex.withLock {
                if (_downloadingId.value != null) return@withLock
                _downloadingId.value = "openai"
            }
            _notice.value = null
            openAIPluginAdapter.fetchAsZip(url)
                .onSuccess { bytes ->
                    pluginManager.installZip(bytes)
                        .onSuccess { info ->
                            autoEnablePlugin(info.id)
                            _notice.value = "已安装并启用 ${info.name}（OpenAI 插件）"
                        }
                        .onFailure { _notice.value = "安装失败: ${it.message}" }
                }
                .onFailure { _notice.value = "获取失败: ${it.message}" }
            _downloadingId.value = null
            refreshInstalled()
        }
    }

    /** DSH 市场列表状态 */
    private val _dshEntries = MutableStateFlow<List<DshMarketPlugin>>(emptyList())
    val dshEntries = _dshEntries.asStateFlow()

    private val _dshLoading = MutableStateFlow(false)
    val dshLoading = _dshLoading.asStateFlow()

    private val _dshError = MutableStateFlow<String?>(null)
    val dshError = _dshError.asStateFlow()

    private val _dshCategories = MutableStateFlow<List<me.rerere.rikkahub.data.api.DshCategory>>(emptyList())
    val dshCategories = _dshCategories.asStateFlow()

    private val _dshUpdated = MutableStateFlow("")
    val dshUpdated = _dshUpdated.asStateFlow()

    /** 拉取 DSH 插件市场列表（实时 feed，失败自动降级 README 解析） */
    fun loadDshMarket() {
        if (_dshLoading.value) return
        viewModelScope.launch {
            _dshLoading.value = true
            _dshError.value = null
            dshMarketDataSource.fetchList()
                .onSuccess { list ->
                    _dshEntries.value = list.plugins
                    _dshCategories.value = list.categories
                    _dshUpdated.value = list.updated
                }
                .onFailure { _dshError.value = "DSH 市场加载失败: ${it.message}" }
            _dshLoading.value = false
        }
    }

    /** 安装 DSH 市场条目：优先走 feed 的 npm tarball 直链（快且稳），否则回退 GitHub 仓库转换 */
    fun installDshMarketEntry(entry: DshMarketPlugin) {
        if (entry.hasTarball) {
            viewModelScope.launch {
                installMutex.withLock {
                    if (_downloadingId.value != null) return@withLock
                    _downloadingId.value = "dsh-${entry.repoRef}"
                }
                _notice.value = null
                dshPluginAdapter.fetchTarballAsZip(entry.tarball!!)
                    .onSuccess { bytes ->
                        pluginManager.installZip(bytes)
                            .onSuccess { info ->
                                autoEnablePlugin(info.id)
                                _notice.value = "已安装并启用 ${info.name}（DSH 插件）"
                            }
                            .onFailure { _notice.value = "安装失败: ${it.message}" }
                    }
                    .onFailure {
                        _notice.value = "tarball 直链获取失败，回退仓库转换: ${it.message}"
                        installDsh(entry.repoRef, downloadingKey = "dsh-${entry.repoRef}")
                    }
                _downloadingId.value = null
                refreshInstalled()
            }
        } else {
            installDsh(entry.repoRef, downloadingKey = "dsh-${entry.repoRef}")
        }
    }

    /** 从 DeepSeek Harness（DSH）插件仓库地址安装：github:owner/repo#ref 自动转换为可迁移能力插件 */
    fun installDsh(repoRef: String, downloadingKey: String = "dsh") {
        viewModelScope.launch {
            installMutex.withLock {
                if (_downloadingId.value != null) return@withLock
                _downloadingId.value = downloadingKey
            }
            _notice.value = null
            dshPluginAdapter.fetchAsZip(repoRef)
                .onSuccess { bytes ->
                    pluginManager.installZip(bytes)
                        .onSuccess { info ->
                            autoEnablePlugin(info.id)
                            _notice.value = "已安装并启用 ${info.name}（DSH 插件）"
                        }
                        .onFailure { _notice.value = "安装失败: ${it.message}" }
                }
                .onFailure { _notice.value = "获取失败: ${it.message}" }
            _downloadingId.value = null
            refreshInstalled()
        }
    }

    /** 安装成功后自动启用插件，避免"装了但没生效" */
    private fun autoEnablePlugin(pluginId: String) {
        // 先同步更新内存态，使紧随其后的 refreshInstalled() 能立即反映"已生效"
        _enabledPlugins = _enabledPlugins + pluginId
        viewModelScope.launch {
            settingsStore.update { it.copy(enabledPlugins = it.enabledPlugins + pluginId) }
            registerMcpServersIfNeeded(pluginId)
            refreshInstalled()
        }
    }

    /** 插件启用时，把插件包内 mcp.json 的远程服务注册到 MCP 设置，使对话中真正可用。
     *  以包内实际存在的 mcp.json 为准（不要求 type=mcp，兼容标准包附带 MCP 配置的场景）；
     *  按 serverUrl 判重，避免解析产生的随机 id 导致重复开关时堆积同一服务 */
    private fun registerMcpServersIfNeeded(pluginId: String) {
        viewModelScope.launch {
            val servers = pluginManager.mcpServersFromPlugin(pluginId)
            if (servers.isEmpty()) return@launch
            val settings = settingsStore.settingsFlow.first()
            val existingUrls = settings.mcpServers.map { it.serverUrl }.toSet()
            val newServers = servers.filter { it.serverUrl !in existingUrls }
            if (newServers.isNotEmpty()) {
                settingsStore.update {
                    it.copy(mcpServers = it.mcpServers + newServers)
                }
                _notice.value = "已注册 ${newServers.size} 个 MCP 服务到 MCP 设置"
            }
        }
    }

    /** 卸载插件时清理其注册的 MCP 服务 */
    private fun unregisterMcpServers(pluginId: String) {
        viewModelScope.launch {
            val servers = pluginManager.mcpServersFromPlugin(pluginId)
            if (servers.isEmpty()) return@launch
            val serverUrls = servers.map { it.serverUrl }.toSet()
            settingsStore.update { s ->
                s.copy(mcpServers = s.mcpServers.filter { it.serverUrl !in serverUrls })
            }
        }
    }

    fun uninstall(pluginId: String) {
        viewModelScope.launch {
            unregisterMcpServers(pluginId)
            pluginManager.uninstall(pluginId)
            val enabled = _enabledPlugins - pluginId
            _enabledPlugins = enabled
            settingsStore.update { it.copy(enabledPlugins = enabled) }
            refreshInstalled()
        }
    }

    /** 提交插件/资源 zip 到市场仓库待审核队列，审核通过后才上架。type 为用户选择的资源类型。 */
    fun upload(zipBytes: ByteArray, type: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _notice.value = null
            if (_githubToken.value.isBlank()) {
                _notice.value = "请先填写 GitHub Token"
                return@launch
            }
            val parsed = pluginManager.parseArchive(zipBytes).getOrNull()
            val entry = if (parsed != null) {
                PluginMarketEntry(
                    id = parsed.id,
                    name = parsed.name,
                    version = parsed.version,
                    description = parsed.description,
                    author = parsed.author,
                    category = parsed.category,
                    repository = parsed.repository,
                    downloadUrl = "https://github.com/${_marketRepo.value}/raw/main/plugins/${parsed.id}-${parsed.version}.zip",
                    type = parsed.type.ifBlank { type },
                    tags = (parsed.tags + type).distinct(),
                )
            } else {
                // 非插件包（skill/mcp/json/其他）：按用户选择的类型登记
                val base = "resource-${System.currentTimeMillis()}"
                PluginMarketEntry(
                    id = base,
                    name = base,
                    version = "1.0.0",
                    description = "通过本地上传的资源包（${PluginCategories.typeLabel(type)}）",
                    author = "",
                    category = "general",
                    repository = "",
                    downloadUrl = "https://github.com/${_marketRepo.value}/raw/main/plugins/$base-1.0.0.zip",
                    type = type,
                    tags = listOf(type),
                )
            }
            marketDataSource.submitPlugin(
                token = _githubToken.value,
                repo = _marketRepo.value,
                zipFileName = "${entry.id}-${entry.version}.zip",
                zipBytes = zipBytes,
                entry = entry,
            ).onSuccess { url ->
                _notice.value = "已提交，等待管理员审核通过后上架"
                onSuccess(url)
            }.onFailure {
                _notice.value = "提交失败: ${it.message}"
            }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    // ---- 酒馆（SillyTavern）角色卡 / 世界书 / 正则 / 预设 ----

    private val _tavernImportedKeys = MutableStateFlow<Set<String>>(emptySet())
    val tavernImportedKeys = _tavernImportedKeys.asStateFlow()

    private val _tavernLorebooks = MutableStateFlow<List<Lorebook>>(emptyList())
    val tavernLorebooks = _tavernLorebooks.asStateFlow()

    private val _tavernImporting = MutableStateFlow(false)
    val tavernImporting = _tavernImporting.asStateFlow()

    // 酒馆市场索引（与插件市场同仓库根目录 tavern.json）
    private val _tavernEntries = MutableStateFlow<List<TavernListing>>(emptyList())
    val tavernEntries = _tavernEntries.asStateFlow()

    private val _tavernLoading = MutableStateFlow(false)
    val tavernLoading = _tavernLoading.asStateFlow()

    private val _tavernError = MutableStateFlow<String?>(null)
    val tavernError = _tavernError.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                _tavernImportedKeys.value = settings.tavernImportedKeys
                _tavernLorebooks.value = settings.lorebooks
            }
        }
    }

    /** 拉取酒馆角色卡市场索引 */
    fun loadTavernMarket() {
        if (_tavernLoading.value) return
        viewModelScope.launch {
            _tavernLoading.value = true
            _tavernError.value = null
            tavernMarketDataSource.fetchDefault()
                .onSuccess { _tavernEntries.value = it }
                .onFailure { _tavernError.value = "酒馆列表加载失败: ${it.message}" }
            _tavernLoading.value = false
        }
    }

    /** 从市场安装条目：按 type 分流（card/worldbook/preset/regex），下载后解析注册 */
    fun installTavernEntry(entry: TavernListing) {
        if (_downloadingId.value != null) return
        viewModelScope.launch {
            _downloadingId.value = "tavern-${entry.id}"
            _notice.value = null
            tavernMarketDataSource.downloadCard(_marketRepo.value, entry.file)
                .onSuccess { bytes ->
                    when (entry.type) {
                        "worldbook" -> importWorldInfo(bytes.toString(Charsets.UTF_8), entry.name)
                        "preset" -> applyPreset(bytes.toString(Charsets.UTF_8))
                        "regex" -> importRegexScripts(bytes.toString(Charsets.UTF_8))
                        else -> {
                            val isPng = entry.file.endsWith(".png", ignoreCase = true)
                            runCatching {
                                val jsonText = withContext(Dispatchers.IO) {
                                    if (isPng) {
                                        TavernPng.extractCharaJson(bytes) ?: error("PNG 中不含酒馆角色数据")
                                    } else {
                                        bytes.toString(Charsets.UTF_8)
                                    }
                                }
                                TavernCardConverter.parseCard(jsonText)
                            }.onSuccess { card ->
                                importCardInternal(card)
                            }.onFailure { e ->
                                _notice.value = "角色卡解析失败: ${e.message}"
                            }
                        }
                    }
                }
                .onFailure { _notice.value = "下载失败: ${it.message}" }
            _downloadingId.value = null
        }
    }

    /** 核心导入：注册为本地助手，内嵌世界书同步注册并关联 */
    private suspend fun importCardInternal(card: TavernCard) {
        val current = settingsStore.settingsFlow.value
        if (current.assistants.any { it.name == card.name }) {
            _notice.value = "助手「${card.name}」已存在，无需重复导入"
            return
        }
        val assistant = TavernCardConverter.toAssistant(card)
        val lorebook = TavernCardConverter.cardToLorebook(card)
        val linkedAssistant = if (lorebook != null) {
            assistant.copy(lorebookIds = assistant.lorebookIds + lorebook.id)
        } else {
            assistant
        }
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants + linkedAssistant,
                lorebooks = if (lorebook != null) settings.lorebooks + lorebook else settings.lorebooks,
                tavernImportedKeys = settings.tavernImportedKeys + importedKeyOf(card),
            )
        }
        _notice.value = "已导入「${card.name}」为本地助手" + if (lorebook != null) "（含世界书）" else ""
    }

    /** 导入解析好的角色卡（UI 内置来源或转换结果），带并发防抖 */
    fun importTavernCard(card: TavernCard) {
        if (_tavernImporting.value) return
        viewModelScope.launch {
            _tavernImporting.value = true
            _notice.value = null
            runCatching { importCardInternal(card) }
                .onFailure { e -> _notice.value = "导入失败: ${e.message}" }
            _tavernImporting.value = false
        }
    }

    /**
     * 从文件字节导入角色卡：JSON 直接解析；PNG 提取 tEXt chara chunk 后 Base64 解码。
     */
    fun importTavernCardFromBytes(bytes: ByteArray, fileName: String, isPng: Boolean) {
        if (_tavernImporting.value) return
        viewModelScope.launch {
            _tavernImporting.value = true
            _notice.value = null
            try {
                val jsonText = withContext(Dispatchers.IO) {
                    if (isPng) {
                        TavernPng.extractCharaJson(bytes) ?: error("该 PNG 不含酒馆角色数据（chara chunk）")
                    } else {
                        bytes.toString(Charsets.UTF_8)
                    }
                }
                val card = TavernCardConverter.parseCard(jsonText)
                importCardInternal(card)
            } catch (e: Throwable) {
                _notice.value = "角色卡导入失败: ${e.message}"
            } finally {
                _tavernImporting.value = false
            }
        }
    }

    /** 导入 SillyTavern 世界书 JSON 为 Lorebook（在 助手详情→提示词注入 中关联启用），按名称去重 */
    fun importWorldInfo(jsonText: String, fileName: String?) {
        viewModelScope.launch {
            _notice.value = null
            runCatching { TavernCardConverter.parseWorldInfo(jsonText, fileName) }
                .onSuccess { book ->
                    val current = settingsStore.settingsFlow.value
                    if (current.lorebooks.any { it.name == book.name }) {
                        _notice.value = "世界书「${book.name}」已存在，跳过重复导入"
                    } else {
                        settingsStore.update { s -> s.copy(lorebooks = s.lorebooks + book) }
                        _notice.value = "已导入世界书「${book.name}」（${book.entries.size} 条目）"
                    }
                }
                .onFailure { e -> _notice.value = "世界书导入失败: ${e.message}" }
        }
    }

    /** 删除已导入的世界书 */
    fun removeLorebook(bookId: Uuid) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(lorebooks = settings.lorebooks.filterNot { it.id == bookId })
            }
        }
    }

    /**
     * 导入 SillyTavern 正则脚本（单个/数组/脚本库均可），
     * 追加到当前选中助手的正则替换列表。
     */
    fun importRegexScripts(jsonText: String) {
        viewModelScope.launch {
            _notice.value = null
            runCatching { TavernCardConverter.parseRegexScripts(jsonText) }
                .onSuccess { scripts ->
                    if (scripts.isEmpty()) {
                        _notice.value = "未在文件中找到可用正则脚本"
                        return@launch
                    }
                    val current = settingsStore.settingsFlow.value
                    val targetId = current.assistantId
                    val applied = current.getCurrentAssistantOrNull()?.let { assistant ->
                        val existingNames = assistant.regexes.map { it.name }.toSet()
                        val fresh = scripts.filter { it.name !in existingNames }
                        if (fresh.isEmpty()) {
                            _notice.value = "正则脚本已存在，跳过重复导入"
                            null
                        } else {
                            val updated = assistant.copy(regexes = assistant.regexes + fresh)
                            Pair(updated, fresh.size)
                        }
                    }
                    if (applied != null) {
                        val (updated, count) = applied
                        settingsStore.update { s ->
                            s.copy(assistants = s.assistants.map { if (it.id == updated.id) updated else it })
                        }
                        _notice.value = "已导入 $count 条正则到当前助手"
                    } else if (_notice.value == null) {
                        _notice.value = "未找到当前助手"
                    }
                }
                .onFailure { e -> _notice.value = "正则导入失败: ${e.message}" }
        }
    }

    /**
     * 应用 SillyTavern 预设的采样参数（temperature/top_p/max_tokens）
     * 到当前选中助手。
     */
    fun applyPreset(jsonText: String) {
        viewModelScope.launch {
            _notice.value = null
            runCatching { TavernCardConverter.parsePreset(jsonText) }
                .onSuccess { preset ->
                    val current = settingsStore.settingsFlow.value
                    val assistant = current.getCurrentAssistantOrNull()
                    if (assistant == null) {
                        _notice.value = "未找到当前助手"
                        return@onSuccess
                    }
                    val updated = assistant.copy(
                        temperature = preset.temperature ?: assistant.temperature,
                        topP = preset.topP ?: assistant.topP,
                        maxTokens = preset.maxTokens ?: assistant.maxTokens,
                    )
                    settingsStore.update { s ->
                        s.copy(assistants = s.assistants.map { if (it.id == updated.id) updated else it })
                    }
                    val parts = buildList {
                        preset.temperature?.let { add("temperature=$it") }
                        preset.topP?.let { add("top_p=$it") }
                        preset.maxTokens?.let { add("max_tokens=$it") }
                    }
                    _notice.value = "预设「${preset.name}」已应用到助手：${parts.joinToString(", ").ifEmpty { "无匹配参数" }}"
                }
                .onFailure { e -> _notice.value = "预设应用失败: ${e.message}" }
        }
    }

    companion object {
        /** 卡片导入去重 key 生成（UI 判断"已导入"用） */
        fun importedKeyOf(card: TavernCard): String =
            "${card.name}@${card.creatorNotes.take(24).ifEmpty { "custom" }}"
    }
}

private fun me.rerere.rikkahub.data.datastore.Settings.getCurrentAssistantOrNull(): me.rerere.rikkahub.data.model.Assistant? =
    assistants.find { it.id == assistantId } ?: assistants.firstOrNull()


data class PluginUiState(
    val info: PluginInfo,
    val status: PluginStatus,
)
