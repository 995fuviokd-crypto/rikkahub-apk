package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/**
 * 「Agent 模式管理」页面 ViewModel：
 * 维护目标工作区、6 种平台 Agent 的安装状态与逐步安装进度。
 */
class SettingAgentVM(
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
        viewModelScope.launch {
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
}
