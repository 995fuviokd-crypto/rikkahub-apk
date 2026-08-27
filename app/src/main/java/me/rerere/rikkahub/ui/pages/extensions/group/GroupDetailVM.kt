package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.group.GroupRunController
import me.rerere.rikkahub.data.ai.group.GroupRunner
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupRun
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.data.repository.GroupRepository

class GroupDetailVM(
    private val id: String,
    private val repository: GroupRepository,
    private val runner: GroupRunner,
    private val runController: GroupRunController,
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

    /**
     * 运行状态来自应用级协调器：页面退出后运行继续，重进页面自动恢复展示。
     */
    val running: StateFlow<Boolean> = runController.runningRuns
        .combine(group) { runsMap, g -> runsMap.containsKey(g?.id) && g != null }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    /** 当前群组活跃运行对应的 runId（供恢复选中） */
    private val activeRunIdForGroup = runController.runningRuns
        .combine(group) { runsMap, g -> runsMap[g?.id] }

    private val _launchError = MutableStateFlow<String?>(null)
    val launchError: StateFlow<String?> = _launchError.asStateFlow()

    init {
        // 默认选中最新一次运行；有活跃运行时优先跟随活跃运行。
        // 用户手动查看历史运行：只要该 run 仍存在就不抢占；
        // 活跃运行结束后自动切回其最终结果页
        viewModelScope.launch {
            combine(runs, activeRunIdForGroup) { list, active ->
                active?.takeIf { list.any { r -> r.id == active } }
                    ?: currentSelectionOrLatest(list)
            }.collect { target ->
                if (_selectedRunId.value != target) {
                    _selectedRunId.value = target
                }
            }
        }
    }

    private fun currentSelectionOrLatest(list: List<GroupRun>): String? {
        val current = _selectedRunId.value ?: return list.firstOrNull()?.id
        return if (list.any { it.id == current }) current else list.firstOrNull()?.id
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
        val runId = runController.runningRuns.value[id] ?: _selectedRunId.value
        if (runId == null || !runController.isRunning(id)) {
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
        val groupValue = group.value
        if (groupValue == null) {
            _launchError.value = "群组尚未加载完成，请稍后再试"
            return
        }
        if (mission.isBlank()) {
            _launchError.value = "请输入要发布的指令内容"
            return
        }
        if (runController.isRunning(id)) {
            _launchError.value = "群组已在运行中，可在下方输入追加指令"
            return
        }
        val runId = runController.launch(groupValue, mission)
        _selectedRunId.value = runId
    }

    fun stopRun() {
        runController.stop(id)
    }
}
