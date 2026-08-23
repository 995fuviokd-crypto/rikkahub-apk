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
import me.rerere.rikkahub.data.model.EndStepConfig
import me.rerere.rikkahub.data.model.ExecutionStatus
import me.rerere.rikkahub.data.model.ExtractMode
import me.rerere.rikkahub.data.model.ExtractStepConfig
import me.rerere.rikkahub.data.model.ForStepConfig
import me.rerere.rikkahub.data.model.HttpStepConfig
import me.rerere.rikkahub.data.model.IfStepConfig
import me.rerere.rikkahub.data.model.MergeStepConfig
import me.rerere.rikkahub.data.model.NodeType
import me.rerere.rikkahub.data.model.OutputStepConfig
import me.rerere.rikkahub.data.model.ShellStepConfig
import me.rerere.rikkahub.data.model.StartStepConfig
import me.rerere.rikkahub.data.model.StepConfig
import me.rerere.rikkahub.data.model.TextStepConfig
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowEdge
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowNode
import me.rerere.rikkahub.data.model.WorkflowRunLogEntry
import me.rerere.rikkahub.data.model.validate
import me.rerere.rikkahub.data.repository.WorkflowRepository
import kotlin.uuid.Uuid

private const val MAX_HISTORY = 100
private const val AUTO_LAYOUT_H_GAP = 280f
private const val AUTO_LAYOUT_V_GAP = 120f
private const val AUTO_LAYOUT_MARGIN = 60f

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

    private val _runLogs = MutableStateFlow<List<WorkflowRunLogEntry>>(emptyList())
    val runLogs: StateFlow<List<WorkflowRunLogEntry>> = _runLogs.asStateFlow()

    private val _runError = MutableStateFlow("")
    val runError: StateFlow<String> = _runError.asStateFlow()

    private val _selectedNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedNodeIds: StateFlow<Set<String>> = _selectedNodeIds.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val undoStack = ArrayDeque<WorkflowGraph>()
    private val redoStack = ArrayDeque<WorkflowGraph>()

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

    /** 点击节点：未选中则单选，已选中则保持当前多选（便于整组拖动）。 */
    fun tapNode(nodeId: String) {
        if (nodeId in _selectedNodeIds.value) return
        _selectedNodeIds.value = setOf(nodeId)
    }

    /** 双击节点：单选并打开编辑面板。 */
    fun doubleTapNode(nodeId: String) {
        _selectedNodeIds.value = setOf(nodeId)
    }

    fun clearSelection() {
        _selectedNodeIds.value = emptySet()
    }

    fun selectNodes(ids: Set<String>) {
        _selectedNodeIds.value = ids
    }

    fun addNode(type: NodeType, worldX: Float, worldY: Float) {
        recordHistory()
        withGraph { g ->
            val node = WorkflowNode(
                id = Uuid.random().toString(),
                type = type,
                name = defaultNodeName(type, g.nodes.size),
                config = defaultConfig(type),
                x = worldX,
                y = worldY,
            )
            g.copy(nodes = g.nodes + node)
        }
    }

    fun updateNode(nodeId: String, name: String, config: StepConfig) {
        recordHistory()
        withGraph { g ->
            g.copy(
                nodes = g.nodes.map { n ->
                    if (n.id == nodeId) n.copy(name = name, config = config) else n
                }
            )
        }
    }

    fun moveNode(nodeId: String, worldX: Float, worldY: Float) {
        recordHistory()
        withGraph { g ->
            g.copy(
                nodes = g.nodes.map { n ->
                    if (n.id == nodeId) n.copy(x = worldX, y = worldY) else n
                }
            )
        }
    }

    /** 批量移动选中节点（相对偏移）。 */
    fun moveSelectedNodes(dx: Float, dy: Float) {
        val ids = _selectedNodeIds.value
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        recordHistory()
        withGraph { g ->
            g.copy(
                nodes = g.nodes.map { n ->
                    if (n.id in ids) n.copy(x = n.x + dx, y = n.y + dy) else n
                }
            )
        }
    }

    fun removeNode(nodeId: String) {
        removeNodes(setOf(nodeId))
    }

    /** 批量删除节点及其相连的连线。 */
    fun removeNodes(nodeIds: Set<String>) {
        if (nodeIds.isEmpty()) return
        recordHistory()
        withGraph { g ->
            g.copy(
                nodes = g.nodes.filterNot { it.id in nodeIds },
                edges = g.edges.filterNot { it.fromNodeId in nodeIds || it.toNodeId in nodeIds },
            )
        }
        _selectedNodeIds.update { it - nodeIds }
    }

    fun removeSelectedNodes() {
        removeNodes(_selectedNodeIds.value)
    }

    /**
     * 添加连线。防自环、防重复；若产生环则拒绝并返回 false。
     */
    fun addEdge(fromId: String, fromPort: String, toId: String): Boolean {
        if (fromId == toId) return false
        val g = workflow.value?.effectiveGraph ?: return false
        val duplicate = g.edges.any { it.fromNodeId == fromId && it.fromPort == fromPort && it.toNodeId == toId }
        if (duplicate) return false
        val candidate = g.copy(
            edges = g.edges + WorkflowEdge(
                id = Uuid.random().toString(),
                fromNodeId = fromId,
                fromPort = fromPort,
                toNodeId = toId,
            )
        )
        if (candidate.validate().any { it.contains("循环") }) return false
        recordHistory()
        withGraph { it.copy(edges = candidate.edges) }
        return true
    }

    fun removeEdge(edgeId: String) {
        recordHistory()
        withGraph { g ->
            g.copy(edges = g.edges.filterNot { it.id == edgeId })
        }
    }

    /** 修改连线的执行条件（null=默认成功传播，success/error/true/false/正则）。 */
    fun updateEdgeCondition(edgeId: String, condition: String?) {
        recordHistory()
        withGraph { g ->
            g.copy(
                edges = g.edges.map { e ->
                    if (e.id == edgeId) e.copy(condition = condition) else e
                }
            )
        }
    }

    fun undo() {
        val current = workflow.value?.effectiveGraph ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
        applyGraph(prev)
    }

    fun redo() {
        val current = workflow.value?.effectiveGraph ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
        applyGraph(next)
    }

    /** 按 DAG 拓扑分层自动排列节点坐标。 */
    fun autoLayout() {
        val g = workflow.value?.effectiveGraph ?: return
        if (g.nodes.isEmpty() || g.validate().isNotEmpty()) return
        recordHistory()
        val incoming = g.edges.groupBy { it.toNodeId }
        val layerOf = mutableMapOf<String, Int>()
        fun computeLayer(nodeId: String): Int {
            layerOf[nodeId]?.let { return it }
            val sources = incoming[nodeId].orEmpty().map { it.fromNodeId }
            val max = if (sources.isEmpty()) 0 else sources.maxOf { computeLayer(it) + 1 }
            layerOf[nodeId] = max
            return max
        }
        g.nodes.forEach { computeLayer(it.id) }
        val byLayer = layerOf.entries.groupBy({ it.value }, { it.key })
        val positions = mutableMapOf<String, Pair<Float, Float>>()
        for ((layer, ids) in byLayer) {
            val sorted = ids.sortedBy { id -> g.nodes.find { it.id == id }?.y ?: 0f }
            sorted.forEachIndexed { index, nodeId ->
                positions[nodeId] = AUTO_LAYOUT_MARGIN + layer * AUTO_LAYOUT_H_GAP to
                    AUTO_LAYOUT_MARGIN + index * AUTO_LAYOUT_V_GAP
            }
        }
        withGraph { graph ->
            graph.copy(
                nodes = graph.nodes.map { n ->
                    positions[n.id]?.let { (x, y) -> n.copy(x = x, y = y) } ?: n
                }
            )
        }
    }

    fun run() {
        val current = workflow.value ?: return
        if (_running.value) return
        if (current.effectiveGraph.validate().isNotEmpty()) return
        viewModelScope.launch {
            _running.value = true
            _runSucceeded.value = null
            _runProgress.value = emptyList()
            _runLogs.value = emptyList()
            _runError.value = ""
            val result = runner.run(workflow = current) { progress ->
                _runProgress.update { list ->
                    val updated = list.toMutableList()
                    val idx = updated.indexOfFirst { it.nodeId == progress.nodeId }
                    if (idx >= 0) {
                        updated[idx] = progress
                    } else {
                        updated.add(progress)
                    }
                    updated
                }
            }
            _runSucceeded.value = result.succeeded
            _runLogs.value = result.logs
            _runError.value = result.error
            _running.value = false
            result.executionRecord?.let { record ->
                repository.saveExecutionRecord(record)
            }
            // 执行统计（total/success/failed/lastExecution）
            val timestamp = result.finishedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            val updated = current.withStats(
                status = if (result.succeeded) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                timestamp = timestamp,
                success = result.succeeded,
            )
            workflow.value = updated
            repository.updateStats(updated)
        }
    }

    fun clearRunResult() {
        _runSucceeded.value = null
        _runProgress.value = emptyList()
        _runLogs.value = emptyList()
        _runError.value = ""
    }

    private fun recordHistory() {
        val current = workflow.value?.effectiveGraph ?: return
        undoStack.addLast(current)
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    private fun applyGraph(graph: WorkflowGraph) {
        workflow.update { it?.copy(graph = graph, steps = emptyList()) }
        save()
    }

    private fun withGraph(silent: Boolean = false, transform: (WorkflowGraph) -> WorkflowGraph) {
        val current = workflow.value ?: return
        val base = current.effectiveGraph
        val newGraph = transform(base)
        workflow.update { it?.copy(graph = newGraph, steps = emptyList()) }
        if (!silent) save()
    }

    private fun save() {
        val current = workflow.value ?: return
        viewModelScope.launch {
            repository.save(current)
        }
    }

    private fun defaultNodeName(type: NodeType, index: Int): String = when (type) {
        NodeType.START -> "开始"
        NodeType.END -> "结束"
        NodeType.TEXT -> "文本节点"
        NodeType.AI -> "AI 节点"
        NodeType.SHELL -> "命令节点"
        NodeType.HTTP -> "HTTP 节点"
        NodeType.DELAY -> "延迟节点"
        NodeType.IF -> "条件节点"
        NodeType.FOR -> "循环节点"
        NodeType.MERGE -> "汇聚节点"
        NodeType.EXTRACT -> "提取节点"
        NodeType.OUTPUT -> "输出节点"
    }

    private fun defaultConfig(type: NodeType): StepConfig = when (type) {
        NodeType.START -> StartStepConfig()
        NodeType.END -> EndStepConfig()
        NodeType.TEXT -> TextStepConfig(content = "这是一段固定输出")
        NodeType.AI -> AiStepConfig(assistantId = "", prompt = "请根据 {{input.topic}} 生成内容")
        NodeType.SHELL -> ShellStepConfig(command = "echo hello")
        NodeType.HTTP -> HttpStepConfig(url = "https://example.com")
        NodeType.DELAY -> DelayStepConfig(seconds = 1)
        NodeType.IF -> IfStepConfig(condition = "{{node.xxx.output}} != \"\"")
        NodeType.FOR -> ForStepConfig(itemsSource = "[1,2,3]", prompt = "处理 {{item}}", assistantId = "")
        NodeType.MERGE -> MergeStepConfig()
        NodeType.EXTRACT -> ExtractStepConfig(
            mode = ExtractMode.REGEX,
            source = "{{node.xxx.output}}",
            expression = "(\\d+)",
            group = 0,
            defaultValue = "",
        )
        NodeType.OUTPUT -> OutputStepConfig(template = "{{node.start.output}}")
    }
}
