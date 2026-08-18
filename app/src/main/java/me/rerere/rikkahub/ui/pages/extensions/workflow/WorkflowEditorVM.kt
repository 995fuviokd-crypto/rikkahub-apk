package me.rerere.rikkahub.ui.pages.extensions.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.workflow.RunProgress
import me.rerere.rikkahub.data.ai.workflow.WorkflowRunner
import me.rerere.rikkahub.data.model.AiStepConfig
import me.rerere.rikkahub.data.model.DelayStepConfig
import me.rerere.rikkahub.data.model.HttpStepConfig
import me.rerere.rikkahub.data.model.ShellStepConfig
import me.rerere.rikkahub.data.model.StepConfig
import me.rerere.rikkahub.data.model.StepType
import me.rerere.rikkahub.data.model.TextStepConfig
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.rikkahub.data.repository.WorkflowRepository
import kotlin.uuid.Uuid

class WorkflowEditorVM(
    private val id: String,
    private val repository: WorkflowRepository,
    private val runner: WorkflowRunner,
) : ViewModel() {
    val workflow = MutableStateFlow<Workflow?>(null)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _runProgress = MutableStateFlow<List<RunProgress>>(emptyList())
    val runProgress: StateFlow<List<RunProgress>> = _runProgress.asStateFlow()

    private val _runSucceeded = MutableStateFlow<Boolean?>(null)
    val runSucceeded: StateFlow<Boolean?> = _runSucceeded.asStateFlow()

    init {
        viewModelScope.launch {
            workflow.value = repository.loadWorkflow(id)
        }
    }

    fun updateName(name: String) {
        workflow.update { it?.copy(name = name) }
        save()
    }

    fun updateDescription(description: String) {
        workflow.update { it?.copy(description = description) }
        save()
    }

    fun addStep(type: StepType) {
        val current = workflow.value ?: return
        val step = WorkflowStep(
            id = Uuid.random().toString(),
            name = defaultStepName(type, current.steps.size),
            type = type,
            config = defaultConfig(type),
        )
        workflow.update { it?.copy(steps = it.steps + step) }
        save()
    }

    fun updateStep(stepId: String, name: String, config: StepConfig) {
        workflow.update { w ->
            w?.copy(
                steps = w.steps.map { step ->
                    if (step.id == stepId) step.copy(name = name, config = config) else step
                }
            )
        }
        save()
    }

    fun removeStep(stepId: String) {
        workflow.update { w ->
            w?.copy(steps = w.steps.filterNot { it.id == stepId })
        }
        save()
    }

    fun moveStep(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        workflow.update { w ->
            w?.copy(steps = w.steps.toMutableList().apply {
                if (fromIndex in indices && toIndex in indices) {
                    add(toIndex, removeAt(fromIndex))
                }
            })
        }
        save()
    }

    fun run() {
        val current = workflow.value ?: return
        if (_running.value) return
        viewModelScope.launch {
            _running.value = true
            _runSucceeded.value = null
            _runProgress.value = emptyList()
            val result = runner.run(workflow = current) { progress ->
                _runProgress.update { list ->
                    val updated = list.toMutableList()
                    val idx = updated.indexOfFirst { it.stepId == progress.stepId }
                    if (idx >= 0) {
                        updated[idx] = progress
                    } else {
                        updated.add(progress)
                    }
                    updated
                }
            }
            _runSucceeded.value = result.succeeded
            _running.value = false
        }
    }

    fun clearRunResult() {
        _runSucceeded.value = null
        _runProgress.value = emptyList()
    }

    private fun save() {
        val current = workflow.value ?: return
        viewModelScope.launch {
            repository.save(current)
        }
    }

    private fun defaultStepName(type: StepType, index: Int): String = when (type) {
        StepType.TEXT -> "文本步骤"
        StepType.AI -> "AI 生成步骤"
        StepType.SHELL -> "命令步骤"
        StepType.HTTP -> "HTTP 请求步骤"
        StepType.DELAY -> "延迟步骤"
    }

    private fun defaultConfig(type: StepType): StepConfig = when (type) {
        StepType.TEXT -> TextStepConfig(content = "这是一段固定输出")
        StepType.AI -> AiStepConfig(assistantId = "", prompt = "请总结以下内容：\n{{step.1.output}}")
        StepType.SHELL -> ShellStepConfig(command = "echo hello")
        StepType.HTTP -> HttpStepConfig(url = "https://example.com")
        StepType.DELAY -> DelayStepConfig(seconds = 1)
    }
}
