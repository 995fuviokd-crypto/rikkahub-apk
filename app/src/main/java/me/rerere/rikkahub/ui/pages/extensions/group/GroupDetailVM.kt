package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.group.GroupRunner
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupRun
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.data.repository.GroupRepository
import kotlin.uuid.Uuid

class GroupDetailVM(
    private val id: String,
    private val repository: GroupRepository,
    private val runner: GroupRunner,
) : ViewModel() {
    val group: StateFlow<Group?> = repository.getGroup(id)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val runs: StateFlow<List<GroupRun>> = repository.listRuns(id)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedRunId = MutableStateFlow<String?>(null)
    val selectedRunId: StateFlow<String?> = _selectedRunId.asStateFlow()

    val messages: StateFlow<List<GroupMessage>> = _selectedRunId
        .flatMapLatest { runId ->
            if (runId == null) flowOf(emptyList()) else repository.listMessages(runId)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _launchError = MutableStateFlow<String?>(null)
    val launchError: StateFlow<String?> = _launchError.asStateFlow()

    // 消息展示双模式：true=在详情页内联展示，false=进入独立消息页面查看
    private val _inlineMessages = MutableStateFlow(true)
    val inlineMessages: StateFlow<Boolean> = _inlineMessages.asStateFlow()

    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            runs.collect { list ->
                val current = _selectedRunId.value
                if (current == null || list.none { it.id == current }) {
                    _selectedRunId.value = list.firstOrNull()?.id
                }
            }
        }
    }

    fun selectRun(runId: String?) {
        _selectedRunId.value = runId
    }

    fun setInlineMessages(inline: Boolean) {
        _inlineMessages.value = inline
    }

    fun consumeLaunchError() {
        _launchError.value = null
    }

    fun launchRun(mission: String) {
        val group = this.group.value
        if (group == null) {
            _launchError.value = "群组尚未加载完成，请稍后再试"
            return
        }
        if (mission.isBlank()) {
            _launchError.value = "请输入要发布的指令内容"
            return
        }
        val runId = Uuid.random().toString()
        _selectedRunId.value = runId
        runJob?.cancel()
        runJob = viewModelScope.launch {
            _running.value = true
            try {
                runner.run(group, mission, runId = runId)
            } catch (e: CancellationException) {
                val run = repository.getRunById(runId)
                if (run != null && run.status == RunStatus.RUNNING) {
                    repository.upsertRun(
                        run.copy(status = RunStatus.STOPPED, endedAt = System.currentTimeMillis())
                    )
                }
            } finally {
                _running.value = false
            }
        }
    }

    fun stopRun() {
        runJob?.cancel()
    }
}
