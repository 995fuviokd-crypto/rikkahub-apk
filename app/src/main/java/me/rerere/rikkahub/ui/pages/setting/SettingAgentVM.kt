package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.AgentPlatform
import me.rerere.rikkahub.data.ai.agent.AcpEnvironmentManager
import me.rerere.rikkahub.data.ai.agent.AgentEnvStatus
import me.rerere.rikkahub.data.ai.agent.AgentInstallPhase
import me.rerere.rikkahub.data.ai.agent.AgentInstallProgress
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository

/** 离线导入包(.tgz)的 UI 状态 */
sealed interface AgentImportUiState {
    data object Idle : AgentImportUiState

    /** @param fileName 正在导入的文件名 */
    data class Running(val fileName: String) : AgentImportUiState

    /** @param detail 导入结果说明(包含识别到的平台信息) */
    data class Done(val detail: String) : AgentImportUiState
}

/**
 * 「Agent 模式管理」页面 ViewModel：
 * 维护目标工作区、6 种平台 Agent 的安装状态与逐步安装进度。
 * 支持取消进行中的安装, 以及从本地 .tgz 离线包导入 CLI。
 */
class SettingAgentVM(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val environmentManager: AcpEnvironmentManager,
) : ViewModel() {
    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedWorkspaceId = MutableStateFlow<String?>(null)
    val selectedWorkspaceId: StateFlow<String?> = _selectedWorkspaceId

    private val _statuses = MutableStateFlow<Map<AgentPlatform, AgentEnvStatus>>(emptyMap())
    val statuses: StateFlow<Map<AgentPlatform, AgentEnvStatus>> = _statuses

    private val _progress = MutableStateFlow<Map<AgentPlatform, AgentInstallProgress?>>(emptyMap())
    val progress: StateFlow<Map<AgentPlatform, AgentInstallProgress?>> = _progress

    private val _installing = MutableStateFlow<AgentPlatform?>(null)
    val installing: StateFlow<AgentPlatform?> = _installing

    private val _importState = MutableStateFlow<AgentImportUiState>(AgentImportUiState.Idle)
    val importState: StateFlow<AgentImportUiState> = _importState

    private var installJob: Job? = null

    init {
        // 自动选中第一个工作区；工作区变化时同步选中项
        viewModelScope.launch {
            workspaces.collectLatest { list ->
                val current = _selectedWorkspaceId.value
                if (current == null || list.none { it.id == current }) {
                    _selectedWorkspaceId.value = list.firstOrNull()?.id
                }
            }
        }
        // 选中工作区变化时重新检测安装状态
        viewModelScope.launch {
            _selectedWorkspaceId.collectLatest { id ->
                if (id != null) refreshStatuses()
            }
        }
    }

    fun selectWorkspace(id: String) {
        if (_selectedWorkspaceId.value != id) {
            _selectedWorkspaceId.value = id
        }
    }

    private fun selectedRoot(): String? {
        val id = _selectedWorkspaceId.value ?: return null
        return workspaces.value.firstOrNull { it.id == id }?.root
    }

    /** 重新检测所有平台 Agent 的安装状态 */
    fun refreshStatuses() {
        val root = selectedRoot() ?: return
        viewModelScope.launch {
            _statuses.value = AgentPlatform.entries.associateWith { AgentEnvStatus.UNKNOWN }
            AgentPlatform.entries.forEach { platform ->
                val status = environmentManager.checkStatus(root, platform)
                _statuses.value = _statuses.value + (platform to status)
            }
        }
    }

    /** 安装指定平台 Agent 到当前工作区，逐步更新进度 */
    fun install(platform: AgentPlatform) {
        val root = selectedRoot() ?: return
        if (_installing.value != null) return
        _installing.value = platform
        _progress.value = _progress.value + (platform to AgentInstallProgress(AgentInstallPhase.CHECKING, "准备中…"))
        installJob = viewModelScope.launch {
            environmentManager.installWithProgress(root, platform) { progress ->
                _progress.value = _progress.value + (platform to progress)
            }.onSuccess {
                _statuses.value = _statuses.value + (platform to AgentEnvStatus.READY)
            }.onFailure {
                // 失败详情保留在 FAILED 阶段进度中持续展示；同时重查真实环境状态
                val status = environmentManager.checkStatus(root, platform)
                _statuses.value = _statuses.value + (platform to status)
            }
            _installing.value = null
        }
    }

    /** 取消进行中的安装: 协程取消会中断容器内命令并杀掉进程 */
    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
        _installing.value = null
    }

    /**
     * 从系统文件选择器选中的 .tgz npm 包导入安装。
     * 导入完成后重新检测全部平台状态, 命中的平台自动变为「已安装」。
     */
    fun importArchive(uri: Uri) {
        val root = selectedRoot() ?: return
        if (_importState.value is AgentImportUiState.Running) return
        viewModelScope.launch {
            _importState.value = runCatching {
                val fileName = queryDisplayName(uri) ?: "package.tgz"
                _importState.value = AgentImportUiState.Running(fileName)
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取所选文件")
                var logTail = ""
                environmentManager.importPackageArchive(
                    root = root,
                    archiveName = fileName.takeIf { it.lowercase().endsWith(".tgz") || it.lowercase().endsWith(".tar.gz") }
                        ?: "$fileName.tgz",
                    bytes = bytes,
                    onLog = { line -> logTail = (logTail + line).takeLast(400) },
                ).getOrThrow()
                // 重新检测, 让新导入的平台显示为已安装
                refreshStatuses()
                AgentImportUiState.Done(logTail.lineSequence().lastOrNull { it.isNotBlank() } ?: "导入完成")
            }.getOrElse { throwable ->
                AgentImportUiState.Done("导入失败：${throwable.message}")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
