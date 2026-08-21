package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.group.GroupRunner
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupRun
import me.rerere.rikkahub.data.model.MessageKind
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

    fun consumeLaunchError() {
        _launchError.value = null
    }

    /**
     * 运行中追加指令：不重新生成，而是把新指令作为 USER 消息写入当前 run，
     * GroupRunner 会在后续成员调用中读取并注入。
     */
    fun appendInstruction(text: String) {
        val runId = _selectedRunId.value
        if (runId == null) {
            _launchError.value = "当前没有正在进行的运行，请先发布一条指令"
            return
        }
        if (text.isBlank()) {
            _launchError.value = "请输入要追加的指令内容"
            return
        }
        viewModelScope.launch {
            val run = runCatching { repository.getRunById(runId) }.getOrNull()
            if (run == null || run.status != RunStatus.RUNNING) {
                _launchError.value = "当前没有正在进行的运行，请先发布一条指令"
                return@launch
            }
            repository.addMessage(
                runId = runId,
                memberId = GroupRunner.USER_MEMBER_ID,
                content = text,
                kind = MessageKind.USER,
                memberRole = "用户",
            )
        }
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
                withContext(NonCancellable) {
                    val run = repository.getRunById(runId)
                    if (run != null && run.status == RunStatus.RUNNING) {
                        repository.upsertRun(
                            run.copy(status = RunStatus.STOPPED, endedAt = System.currentTimeMillis())
                        )
                    }
                }
                throw e
            } finally {
                _running.value = false
            }
        }
    }

    fun stopRun() {
        runJob?.cancel()
    }
}
