package me.rerere.rikkahub.ui.pages.extensions.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.workflow.WorkflowRunner
import me.rerere.rikkahub.data.ai.workflow.WorkflowRunResult
import me.rerere.rikkahub.data.db.entity.WorkflowEntity
import me.rerere.rikkahub.data.repository.WorkflowRepository

class WorkflowListVM(
    private val repository: WorkflowRepository,
    private val runner: WorkflowRunner,
) : ViewModel() {
    val workflows: StateFlow<List<WorkflowEntity>> = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _runningId = MutableStateFlow<String?>(null)
    val runningId: StateFlow<String?> = _runningId.asStateFlow()

    private val _runResult = MutableStateFlow<WorkflowRunResult?>(null)
    val runResult: StateFlow<WorkflowRunResult?> = _runResult.asStateFlow()

    suspend fun create(): String? {
        return runCatching { repository.create("新工作流") }
            .getOrNull()
            ?.id
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    fun run(workflowId: String) {
        viewModelScope.launch {
            _runningId.value = workflowId
            _runResult.value = null
            val workflow = repository.loadWorkflow(workflowId)
            if (workflow != null) {
                _runResult.value = runner.run(workflow = workflow)
            }
            _runningId.value = null
        }
    }

    fun clearRunResult() {
        _runResult.value = null
    }
}
