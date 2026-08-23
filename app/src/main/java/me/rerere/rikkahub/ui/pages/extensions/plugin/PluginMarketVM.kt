package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import me.rerere.rikkahub.data.api.communityPluginIdFor

class PluginMarketVM(
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
    private val marketDataSource: PluginMarketDataSource,
    private val openAIPluginAdapter: OpenAIPluginAdapter,
    private val communityDataSource: CommunityMarketDataSource,
    private val dshPluginAdapter: DshPluginAdapter,
    private val dshMarketDataSource: DshMarketDataSource,
) : ViewModel() {
    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed = _installed.asStateFlow()

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

    /**
     * 社区市场更新检测：已安装 community-* 插件的 version 与市场 latestVersion 对比，
     * 版本不同即提示可更新（重装覆盖，installZip 自带旧版备份回滚）。
     */
    fun communityUpdateFor(entry: CommunityListItem, installed: List<InstalledPlugin>): String? {
        val marketVersion = entry.latestVersion.version.trim().takeIf { it.isNotEmpty() } ?: return null
        val pid = communityPluginIdFor(entry.id)
        val current = installed.firstOrNull {
            it.id == pid || it.id == pid.replaceFirst("community-", "operit-")
        } ?: return null
        val localVersion = current.info?.version?.trim().orEmpty()
        return if (localVersion.isNotEmpty() && !localVersion.equals(marketVersion, ignoreCase = true)) {
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
        if (_communityInstallingId.value != null) return
        viewModelScope.launch {
            _communityInstallingId.value = entry.id
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
            if (pluginId in enabled) registerMcpServersIfNeeded(pluginId)
            refreshInstalled()
        }
    }

    fun install(entry: PluginMarketEntry) {
        if (_downloadingId.value != null) return
        viewModelScope.launch {
            _downloadingId.value = entry.id
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
        if (_downloadingId.value != null) return
        viewModelScope.launch {
            _downloadingId.value = "openai"
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

    private var dshSortByStars = true

    /** 拉取 DSH 插件市场列表（实时 feed，失败自动降级 README 解析） */
    fun loadDshMarket() {
        if (_dshLoading.value) return
        viewModelScope.launch {
            _dshLoading.value = true
            _dshError.value = null
            dshMarketDataSource.fetchList()
                .onSuccess { list ->
                    dshSortByStars = true
                    _dshEntries.value = list.plugins
                        .sortedWith(compareByDescending<DshMarketPlugin> { it.stars }.thenBy { it.name })
                    _dshCategories.value = list.categories
                    _dshUpdated.value = list.updated
                }
                .onFailure { _dshError.value = "DSH 市场加载失败: ${it.message}" }
            _dshLoading.value = false
        }
    }

    /** 安装 DSH 市场条目：GitHub 仓库转换为可迁移能力插件（技能/工具定义/npm CLI 工作区命令） */
    fun installDshMarketEntry(entry: DshMarketPlugin) {
        installDsh(entry.repoRef, downloadingKey = "dsh-${entry.repoRef}")
    }

    /** 从 DeepSeek Harness（DSH）插件仓库地址安装：github:owner/repo#ref 自动转换为可迁移能力插件 */
    fun installDsh(repoRef: String, downloadingKey: String = "dsh") {
        if (_downloadingId.value != null) return
        viewModelScope.launch {
            _downloadingId.value = downloadingKey
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
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            val enabled = settings.enabledPlugins + pluginId
            _enabledPlugins = enabled
            settingsStore.update { it.copy(enabledPlugins = enabled) }
            registerMcpServersIfNeeded(pluginId)
        }
    }

    /** mcp 类型插件启用时，把插件包内 mcp.json 的服务注册到 MCP 设置，使对话中真正可用 */
    private fun registerMcpServersIfNeeded(pluginId: String) {
        viewModelScope.launch {
            val info = pluginManager.loadInfo(pluginId) ?: return@launch
            if (info.type != "mcp") return@launch
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

    fun uninstall(pluginId: String) {
        viewModelScope.launch {
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
}

data class PluginUiState(
    val info: PluginInfo,
    val status: PluginStatus,
)
