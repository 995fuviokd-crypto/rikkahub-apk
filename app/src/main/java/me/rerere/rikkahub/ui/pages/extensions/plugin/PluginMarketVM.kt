package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.api.PluginMarketDataSource
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.OpenAIPluginAdapter
import me.rerere.rikkahub.data.plugin.PluginCategories
import me.rerere.rikkahub.data.plugin.PluginInfo
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.plugin.PluginMarketEntry
import me.rerere.rikkahub.data.plugin.PluginStatus

class PluginMarketVM(
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
    private val marketDataSource: PluginMarketDataSource,
    private val openAIPluginAdapter: OpenAIPluginAdapter,
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
                    .onSuccess { _notice.value = "已安装 ${it.name} ${it.version}" }
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
                .onSuccess { _notice.value = "已安装 ${it.name} ${it.version}" }
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
                        .onSuccess { _notice.value = "已安装 ${it.name}（OpenAI 插件）" }
                        .onFailure { _notice.value = "安装失败: ${it.message}" }
                }
                .onFailure { _notice.value = "获取失败: ${it.message}" }
            _downloadingId.value = null
            refreshInstalled()
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

    /** 上传插件/资源 zip 到用户 GitHub 仓库并更新索引。type 为用户选择的资源类型。 */
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
            marketDataSource.uploadPlugin(
                token = _githubToken.value,
                repo = _marketRepo.value,
                zipFileName = "${entry.id}-${entry.version}.zip",
                zipBytes = zipBytes,
                entry = entry,
            ).onSuccess { url ->
                _notice.value = "上传成功"
                onSuccess(url)
                loadMarket()
            }.onFailure {
                _notice.value = "上传失败: ${it.message}"
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
