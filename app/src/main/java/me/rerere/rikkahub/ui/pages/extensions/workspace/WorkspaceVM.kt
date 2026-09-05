package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress

class WorkspaceVM(
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    fun create(name: String, imageUrl: String? = null) {
        viewModelScope.launch {
            _installProgress.value = null
            _installError.value = null
            var created: WorkspaceEntity? = null
            runCatching {
                val workspace = repository.create(name)
                created = workspace
                if (!imageUrl.isNullOrBlank()) {
                    repository.installRootfs(workspace.id, imageUrl) { progress ->
                        _installProgress.value = progress
                    }
                }
            }.onFailure { e ->
                if (e !is CancellationException) {
                    _installError.value = e.message ?: "Installation failed"
                    // 镜像安装失败时清理刚创建的工作区, 避免列表积累残缺记录;
                    // 用户重试会新建干净工作区, 而非在半成品上叠加
                    created?.let { workspace ->
                        runCatching { repository.delete(workspace.id) }
                    }
                }
            }.onSuccess {
                _installProgress.value = null
            }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            terminalSessionManager.closeWorkspace(workspace.root)
            repository.delete(workspace.id)
        }
    }
}
