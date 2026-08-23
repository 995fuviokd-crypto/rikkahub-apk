package me.rerere.rikkahub.ui.pages.extensions.workflow

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AlignLeft
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.CommandLine
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Flowchart02
import me.rerere.hugeicons.stroke.GitBranch
import me.rerere.hugeicons.stroke.GitMerge
import me.rerere.hugeicons.stroke.Globe
import me.rerere.hugeicons.stroke.FitToScreen
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Redo
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.SquareLock02
import me.rerere.hugeicons.stroke.StartUp01
import me.rerere.hugeicons.stroke.Undo
import me.rerere.rikkahub.data.ai.workflow.RunProgress
import me.rerere.rikkahub.data.ai.workflow.StepStatus
import me.rerere.rikkahub.data.model.AiStepConfig
import me.rerere.rikkahub.data.model.DelayStepConfig
import me.rerere.rikkahub.data.model.EndStepConfig
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
import me.rerere.rikkahub.data.model.WorkflowEdge
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowNode
import me.rerere.rikkahub.data.model.WorkflowRunLogEntry
import me.rerere.rikkahub.data.model.WorkflowLogLevel
import me.rerere.rikkahub.data.model.validate
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GRID_SPACING = 40f
private const val SNAP_SPACING = 20f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorPage(
    id: String,
    vm: WorkflowEditorVM = koinViewModel(parameters = { parametersOf(id) }),
) {
    val workflow by vm.workflow.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val runProgress by vm.runProgress.collectAsStateWithLifecycle()
    val runSucceeded by vm.runSucceeded.collectAsStateWithLifecycle()
    val runLogs by vm.runLogs.collectAsStateWithLifecycle()
    val runError by vm.runError.collectAsStateWithLifecycle()
    val selectedNodeIds by vm.selectedNodeIds.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddNodeSheet by remember { mutableStateOf(false) }
    val graph = workflow?.effectiveGraph
    val context = LocalContext.current
    val selectedNodeId = selectedNodeIds.singleOrNull()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(workflow?.name ?: "工作流") },
                navigationIcon = { BackButton() },
                actions = {
                    if (selectedNodeIds.size > 1) {
                        IconButton(
                            onClick = vm::removeSelectedNodes,
                            enabled = !running,
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = "删除选中节点（${selectedNodeIds.size}）", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(
                        onClick = vm::undo,
                        enabled = canUndo && !running,
                    ) {
                        Icon(HugeIcons.Undo, contentDescription = "撤销")
                    }
                    IconButton(
                        onClick = vm::redo,
                        enabled = canRedo && !running,
                    ) {
                        Icon(HugeIcons.Redo, contentDescription = "重做")
                    }
                    IconButton(
                        onClick = vm::autoLayout,
                        enabled = workflow != null && !running,
                    ) {
                        Icon(HugeIcons.Layers01, contentDescription = "自动布局")
                    }
                    IconButton(
                        onClick = { vm.run() },
                        enabled = !running && graph?.validate()?.isEmpty() == true,
                    ) {
                        if (running) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(HugeIcons.Play, contentDescription = "运行")
                        }
                    }
                    IconButton(
                        onClick = { showAddNodeSheet = true },
                        enabled = workflow != null,
                    ) {
                        Icon(HugeIcons.Add01, contentDescription = "添加节点")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (graph != null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                WorkflowGraphCanvas(
                    graph = graph,
                    runProgress = runProgress,
                    selectedNodeIds = selectedNodeIds,
                    onTapNode = vm::tapNode,
                    onDoubleTapNode = vm::doubleTapNode,
                    onMoveNode = vm::moveNode,
                    onMoveSelected = { dx, dy -> vm.moveSelectedNodes(dx, dy) },
                    onSelectBox = { ids -> vm.selectNodes(ids) },
                    onClearSelection = vm::clearSelection,
                    onAddEdge = { fromId, fromPort, toId ->
                        val ok = vm.addEdge(fromId, fromPort, toId)
                        if (!ok) {
                            Toast.makeText(context, "无法连接：重复连线或会产生循环", Toast.LENGTH_SHORT).show()
                        }
                        ok
                    },
                    onOpenEdit = { vm.doubleTapNode(it) },
                )
                if (runSucceeded != null) {
                    RunSummaryBar(
                        succeeded = runSucceeded == true,
                        progress = runProgress,
                        running = running,
                        logs = runLogs,
                        error = runError,
                        onDismiss = vm::clearRunResult,
                    )
                }
            }
        }
    }

    if (showAddNodeSheet) {
        AddNodeSheet(
            onDismiss = { showAddNodeSheet = false },
            onSelect = { type ->
                showAddNodeSheet = false
                val n = graph?.nodes?.size ?: 0
                vm.addNode(type, 200f + n * 40f, 200f + n * 30f)
            },
        )
    }

    if (selectedNodeId != null && graph != null) {
        val node = graph.nodes.find { it.id == selectedNodeId }
        if (node != null) {
            NodeEditSheet(
                node = node,
                graph = graph,
                onDismiss = { vm.clearSelection() },
                onUpdate = { name, config -> vm.updateNode(node.id, name, config) },
                onRemove = { vm.removeNode(node.id) },
                onRemoveEdge = vm::removeEdge,
                onUpdateEdgeCondition = vm::updateEdgeCondition,
            )
        } else {
            vm.clearSelection()
        }
    }
}

@Composable
private fun WorkflowGraphCanvas(
    graph: WorkflowGraph,
    runProgress: List<RunProgress>,
    selectedNodeIds: Set<String>,
    onTapNode: (String) -> Unit,
    onDoubleTapNode: (String) -> Unit,
    onMoveNode: (String, Float, Float) -> Unit,
    onMoveSelected: (Float, Float) -> Unit,
    onSelectBox: (Set<String>) -> Unit,
    onClearSelection: () -> Unit,
    onAddEdge: (String, String, String) -> Boolean,
    onOpenEdit: (String) -> Unit,
) {
    val density = LocalDensity.current
    val nodeW = with(density) { 180.dp.toPx() }
    val nodeH = with(density) { 72.dp.toPx() }
    val portRadius = with(density) { 7.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()

    val scaleState = remember { mutableFloatStateOf(1f) }
    val offsetState = remember { mutableStateOf(Offset(40f, 40f)) }
    var dragOverride by remember { mutableStateOf<Map<String, Offset>?>(null) }
    var linkPreview by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var linkHoverPort by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectionBox by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var gestureStartWorld by remember { mutableStateOf<Map<String, Offset>?>(null) }
    val canvasSize = remember { mutableStateOf(IntSize.Zero) }
    var lastTapNode by remember { mutableStateOf<String?>(null) }
    var lastTapTime by remember { mutableStateOf(0L) }

    val progressById = runProgress.associateBy { it.nodeId }
    val colorScheme = MaterialTheme.colorScheme
    val titleStyle = MaterialTheme.typography.bodyMedium.copy(color = colorScheme.onSurface)
    val subStyle = MaterialTheme.typography.labelSmall.copy(color = colorScheme.onSurfaceVariant)

    fun nodeWorld(node: WorkflowNode): Offset {
        val o = dragOverride
        return o?.get(node.id) ?: Offset(node.x, node.y)
    }

    fun nodeScreen(node: WorkflowNode): Offset {
        val world = nodeWorld(node)
        return Offset(world.x * scaleState.floatValue + offsetState.value.x, world.y * scaleState.floatValue + offsetState.value.y)
    }

    fun portWorld(node: WorkflowNode, port: String): Offset {
        val base = nodeWorld(node)
        return when (port) {
            "in" -> base + Offset(0f, nodeH / 2)
            "true" -> base + Offset(nodeW, nodeH / 4)
            "false" -> base + Offset(nodeW, nodeH * 3 / 4)
            else -> base + Offset(nodeW, nodeH / 2)
        }
    }

    fun portScreen(node: WorkflowNode, port: String): Offset {
        val p = portWorld(node, port)
        return Offset(p.x * scaleState.floatValue + offsetState.value.x, p.y * scaleState.floatValue + offsetState.value.y)
    }

    fun hitTest(screen: Offset): Pair<WorkflowNode?, String?> {
        val s = scaleState.floatValue
        val o = offsetState.value
        for (node in graph.nodes) {
            for (port in outputPorts(node)) {
                val p = portWorld(node, port)
                val screenP = Offset(p.x * s + o.x, p.y * s + o.y)
                if ((screen - screenP).getDistance() < portRadius * 3f) {
                    return node to port
                }
            }
        }
        for (node in graph.nodes.reversed()) {
            val p = nodeScreen(node)
            val rect = Rect(p.x, p.y, p.x + nodeW, p.y + nodeH)
            if (rect.contains(screen)) return node to null
        }
        return null to null
    }

    fun hitPort(screen: Offset): Pair<WorkflowNode?, String?> {
        val s = scaleState.floatValue
        val o = offsetState.value
        for (node in graph.nodes) {
            for (port in outputPorts(node)) {
                val p = portWorld(node, port)
                val screenP = Offset(p.x * s + o.x, p.y * s + o.y)
                if ((screen - screenP).getDistance() < portRadius * 3f) {
                    return node to port
                }
            }
        }
        return null to null
    }

    fun zoomTo(newScale: Float) {
        val cs = canvasSize.value
        if (cs.width == 0 || cs.height == 0) return
        val center = Offset(cs.width / 2f, cs.height / 2f)
        val s = newScale.coerceIn(0.2f, 3f)
        offsetState.value = center - (center - offsetState.value) * (s / scaleState.floatValue)
        scaleState.floatValue = s
    }

    fun zoomBy(factor: Float) = zoomTo(scaleState.floatValue * factor)

    fun fitToContent() {
        if (graph.nodes.isEmpty()) return
        val cs = canvasSize.value
        if (cs.width == 0 || cs.height == 0) return
        val minX = graph.nodes.minOf { it.x }
        val maxX = graph.nodes.maxOf { it.x } + nodeW
        val minY = graph.nodes.minOf { it.y }
        val maxY = graph.nodes.maxOf { it.y } + nodeH
        val pad = 56f
        val contentW = (maxX - minX).coerceAtLeast(1f)
        val contentH = (maxY - minY).coerceAtLeast(1f)
        val s = minOf((cs.width - pad * 2) / contentW, (cs.height - pad * 2) / contentH, 1f).coerceAtLeast(0.2f)
        scaleState.floatValue = s
        offsetState.value = Offset(
            (cs.width - contentW * s) / 2f - minX * s,
            (cs.height - contentH * s) / 2f - minY * s,
        )
    }

    Box {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize.value = it }
                .pointerInput(graph) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val downScreen = down.position
                        val (hitNode, hitPort) = hitTest(downScreen)
                        var mode: GestureMode = GestureMode.NONE
                        var dragAccum = Offset.Zero
                        var boxStart = downScreen
                        var tapCandidate = true
                        if (hitPort != null) {
                            mode = GestureMode.LINK
                            linkPreview = portScreen(hitNode!!, hitPort) to downScreen
                            linkHoverPort = null
                            tapCandidate = false
                        } else if (hitNode != null) {
                            val inSelection = hitNode.id in selectedNodeIds
                            if (inSelection && selectedNodeIds.size > 1) {
                                mode = GestureMode.DRAG_SELECTED
                                gestureStartWorld = selectedNodeIds.associateWith { id ->
                                    graph.nodes.find { it.id == id }?.let { Offset(it.x, it.y) } ?: Offset.Zero
                                }
                                dragAccum = Offset.Zero
                            } else {
                                mode = GestureMode.DRAG_NODE
                                dragOverride = mapOf(hitNode.id to Offset(hitNode.x, hitNode.y))
                                gestureStartWorld = mapOf(hitNode.id to Offset(hitNode.x, hitNode.y))
                                onTapNode(hitNode.id)
                            }
                        } else {
                            mode = GestureMode.PAN
                            boxStart = downScreen
                            onClearSelection()
                        }
                        var wasMultiTouch = false
                        var lastEvent: androidx.compose.ui.input.pointer.PointerInputChange? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val changed = event.changes.firstOrNull() ?: break
                            lastEvent = changed
                            if (event.changes.size >= 2) {
                                wasMultiTouch = true
                                mode = GestureMode.NONE
                                dragOverride = null
                                linkPreview = null
                                linkHoverPort = null
                                selectionBox = null
                                val p1 = event.changes[0].position
                                val p2 = event.changes[1].position
                                val prevP1 = event.changes[0].previousPosition
                                val prevP2 = event.changes[1].previousPosition
                                val newDist = (p2 - p1).getDistance()
                                val oldDist = (prevP2 - prevP1).getDistance()
                                if (oldDist > 0f && newDist > 0f) {
                                    val newScale = (scaleState.floatValue * newDist / oldDist).coerceIn(0.2f, 3f)
                                    val centroid = (p1 + p2) / 2f
                                    val prevCentroid = (prevP1 + prevP2) / 2f
                                    offsetState.value = centroid - (centroid - offsetState.value) * (newScale / scaleState.floatValue)
                                    scaleState.floatValue = newScale
                                }
                                offsetState.value += (p1 + p2) / 2f - (prevP1 + prevP2) / 2f
                            } else {
                                if (wasMultiTouch) {
                                    mode = GestureMode.NONE
                                    wasMultiTouch = false
                                }
                                when (mode) {
                                    GestureMode.PAN -> {
                                        val delta = changed.positionChange()
                                        if (delta.getDistance() > 2.dp.toPx()) {
                                            mode = GestureMode.SELECT_BOX
                                            selectionBox = boxStart to downScreen
                                            tapCandidate = false
                                        }
                                    }
                                    GestureMode.SELECT_BOX -> {
                                        selectionBox = boxStart to changed.position
                                        tapCandidate = false
                                    }
                                    GestureMode.DRAG_NODE -> {
                                        val node = hitNode ?: break
                                        val delta = changed.positionChange() / scaleState.floatValue
                                        dragAccum += delta
                                        tapCandidate = tapCandidate && delta.getDistance() < 1f
                                        dragOverride = mapOf(
                                            node.id to Offset(
                                                snap(gestureStartWorld!![node.id]!!.x + dragAccum.x),
                                                snap(gestureStartWorld!![node.id]!!.y + dragAccum.y),
                                            )
                                        )
                                    }
                                    GestureMode.DRAG_SELECTED -> {
                                        val delta = changed.positionChange() / scaleState.floatValue
                                        dragAccum += delta
                                        tapCandidate = false
                                        val base = gestureStartWorld ?: emptyMap()
                                        dragOverride = base.mapValues { (id, origin) ->
                                            Offset(
                                                snap(origin.x + dragAccum.x),
                                                snap(origin.y + dragAccum.y),
                                            )
                                        }
                                    }
                                    GestureMode.LINK -> {
                                        linkPreview = linkPreview?.copy(second = changed.position)
                                        val (targetNode, targetPort) = hitPort(changed.position)
                                        linkHoverPort = if (targetNode != null && targetNode.id != hitNode?.id && targetPort != null) {
                                            targetNode.id to targetPort
                                        } else null
                                    }
                                    GestureMode.NONE -> Unit
                                }
                            }
                            changed.consume()
                            if (event.changes.all { it.changedToUp() }) break
                        }
                        val up = lastEvent
                        when (mode) {
                            GestureMode.DRAG_NODE -> {
                                val node = hitNode
                                if (node != null) {
                                    if (tapCandidate && up != null) {
                                        val now = System.currentTimeMillis()
                                        val isDouble = node.id == lastTapNode && now - lastTapTime < 350L
                                        lastTapNode = node.id
                                        lastTapTime = now
                                        if (isDouble) {
                                            onOpenEdit(node.id)
                                        }
                                    } else {
                                        onMoveNode(
                                            node.id,
                                            snap(gestureStartWorld!![node.id]!!.x + dragAccum.x),
                                            snap(gestureStartWorld!![node.id]!!.y + dragAccum.y),
                                        )
                                    }
                                }
                                dragOverride = null
                            }
                            GestureMode.DRAG_SELECTED -> {
                                if (dragAccum.getDistance() > 0.01f) {
                                    onMoveSelected(snap(dragAccum.x), snap(dragAccum.y))
                                }
                                dragOverride = null
                            }
                            GestureMode.SELECT_BOX -> {
                                val box = selectionBox ?: (boxStart to downScreen)
                                val minX = minOf(box.first.x, box.second.x)
                                val maxX = maxOf(box.first.x, box.second.x)
                                val minY = minOf(box.first.y, box.second.y)
                                val maxY = maxOf(box.first.y, box.second.y)
                                if (maxX - minX > 4f && maxY - minY > 4f) {
                                    val s = scaleState.floatValue
                                    val o = offsetState.value
                                    val hit = graph.nodes.filter { node ->
                                        val p = Offset(node.x, node.y)
                                        val screenP = Offset(p.x * s + o.x, p.y * s + o.y)
                                        Rect(screenP.x, screenP.y, screenP.x + nodeW, screenP.y + nodeH).let { r ->
                                            r.left < maxX && r.right > minX && r.top < maxY && r.bottom > minY
                                        }
                                    }.map { it.id }
                                    if (hit.isNotEmpty()) onSelectBox(hit.toSet())
                                }
                                selectionBox = null
                            }
                            GestureMode.LINK -> {
                                linkPreview = null
                                linkHoverPort = null
                                if (up != null) {
                                    val (targetNode, _) = hitTest(up.position)
                                    if (targetNode != null && targetNode.id != hitNode?.id) {
                                        onAddEdge(hitNode!!.id, hitPort!!, targetNode.id)
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                },
        ) {
            drawGrid(scaleState.floatValue, offsetState.value)

            val edgeColor = { status: StepStatus? ->
                when (status) {
                    StepStatus.FAILED -> colorScheme.error
                    StepStatus.SUCCESS -> Color(0xFF2E7D32)
                    StepStatus.SKIPPED -> colorScheme.outlineVariant
                    else -> colorScheme.outline
                }
            }

            for (edge in graph.edges) {
                val from = graph.nodes.find { it.id == edge.fromNodeId } ?: continue
                val to = graph.nodes.find { it.id == edge.toNodeId } ?: continue
                val start = portWorld(from, edge.fromPort)
                val end = portWorld(to, edge.toPort)
                val startScreen = Offset(start.x * scaleState.floatValue + offsetState.value.x, start.y * scaleState.floatValue + offsetState.value.y)
                val endScreen = Offset(end.x * scaleState.floatValue + offsetState.value.x, end.y * scaleState.floatValue + offsetState.value.y)
                drawSmoothStepEdge(startScreen, endScreen, edgeColor(progressById[to.id]?.status))
            }

            val preview = linkPreview
            if (preview != null) {
                val color = if (linkHoverPort != null) colorScheme.tertiary else colorScheme.primary
                drawSmoothStepEdge(preview.first, preview.second, color)
            }

            val box = selectionBox
            if (box != null) {
                val minX = minOf(box.first.x, box.second.x)
                val maxX = maxOf(box.first.x, box.second.x)
                val minY = minOf(box.first.y, box.second.y)
                val maxY = maxOf(box.first.y, box.second.y)
                drawRect(
                    color = colorScheme.primary.copy(alpha = 0.12f),
                    topLeft = Offset(minX, minY),
                    size = Size(maxX - minX, maxY - minY),
                )
                drawRect(
                    color = colorScheme.primary,
                    topLeft = Offset(minX, minY),
                    size = Size(maxX - minX, maxY - minY),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            for (node in graph.nodes) {
                val p = nodeScreen(node)
                val status = progressById[node.id]?.status
                val isSelected = node.id in selectedNodeIds
                val borderColor = when {
                    isSelected -> colorScheme.primary
                    status == StepStatus.SUCCESS -> Color(0xFF2E7D32)
                    status == StepStatus.FAILED -> colorScheme.error
                    status == StepStatus.RUNNING -> colorScheme.primary
                    status == StepStatus.SKIPPED -> colorScheme.outlineVariant
                    else -> colorScheme.outlineVariant
                }
                val fillColor = when {
                    node.type == NodeType.START -> colorScheme.primaryContainer
                    node.type == NodeType.END -> colorScheme.tertiaryContainer
                    status == StepStatus.FAILED -> colorScheme.errorContainer.copy(alpha = 0.4f)
                    status == StepStatus.SUCCESS -> Color(0xFFE8F5E9)
                    else -> colorScheme.surface
                }
                drawNode(
                    node = node,
                    topLeft = p,
                    nodeW = nodeW,
                    nodeH = nodeH,
                    borderColor = borderColor,
                    fillColor = fillColor,
                    titleStyle = titleStyle,
                    subStyle = subStyle,
                    isSelected = isSelected,
                    textMeasurer = textMeasurer,
                )
                val hovered = linkHoverPort?.first == node.id
                drawPorts(node, p, nodeW, nodeH, portRadius, edgeColor(status), hovered)
            }

            drawMinimap(
                graph = graph,
                nodeW = nodeW,
                nodeH = nodeH,
                scale = scaleState.floatValue,
                offset = offsetState.value,
                canvasSize = size,
                nodeColor = colorScheme.primary,
                viewportColor = colorScheme.onPrimary,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZoomControlButton(HugeIcons.FitToScreen, "适应全部") { fitToContent() }
            ZoomControlButton(HugeIcons.Add01, "放大") { zoomBy(1.25f) }
            ZoomControlButton(HugeIcons.MinusSign, "缩小") { zoomBy(0.8f) }
        }
    }
}

@Composable
private fun ZoomControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .shadow(2.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.primary)
    }
}

private enum class GestureMode { NONE, PAN, SELECT_BOX, DRAG_NODE, DRAG_SELECTED, LINK }

private fun outputPorts(node: WorkflowNode): List<String> =
    if (node.type == NodeType.IF) listOf("true", "false") else listOf("out")

private fun snap(value: Float): Float = (value / SNAP_SPACING).roundToInt() * SNAP_SPACING

private fun DrawScope.drawGrid(scale: Float, offset: Offset) {
    val spacing = GRID_SPACING * scale
    if (spacing < 6f) return
    val color = Color(0x18000000)
    var x = offset.x % spacing
    while (x < size.width) {
        drawLine(color = color, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1f)
        x += spacing
    }
    var y = offset.y % spacing
    while (y < size.height) {
        drawLine(color = color, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
        y += spacing
    }
}

private fun DrawScope.drawSmoothStepEdge(start: Offset, end: Offset, color: Color) {
    val strokeWidth = 2.dp.toPx()
    if (abs(end.y - start.y) < 1f) {
        drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth)
        return
    }
    val radius = minOf(10.dp.toPx(), abs(end.y - start.y) / 2f)
    val gap = end.x - start.x
    val offset = if (gap > 0) minOf(28.dp.toPx(), gap / 2f) else 28.dp.toPx()
    val dir = if (gap >= 0f) 1f else -1f
    val sign = if (end.y >= start.y) 1f else -1f
    val cornerX = start.x + dir * offset
    val path = Path().apply {
        moveTo(start.x, start.y)
        lineTo(cornerX - dir * radius, start.y)
        quadraticTo(cornerX, start.y, cornerX, start.y + sign * radius)
        lineTo(cornerX, end.y - sign * radius)
        quadraticTo(cornerX, end.y, cornerX + dir * radius, end.y)
        lineTo(end.x, end.y)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth))
}

private fun DrawScope.drawNode(
    node: WorkflowNode,
    topLeft: Offset,
    nodeW: Float,
    nodeH: Float,
    borderColor: Color,
    fillColor: Color,
    titleStyle: TextStyle,
    subStyle: TextStyle,
    isSelected: Boolean,
    textMeasurer: TextMeasurer,
) {
    val radius = CornerRadius(12.dp.toPx())
    drawRoundRect(color = fillColor, topLeft = topLeft, size = Size(nodeW, nodeH), cornerRadius = radius)
    drawRoundRect(
        color = borderColor,
        topLeft = topLeft,
        size = Size(nodeW, nodeH),
        cornerRadius = radius,
        style = Stroke(width = if (isSelected) 2.5.dp.toPx() else 1.5.dp.toPx()),
    )
    drawText(
        textMeasurer = textMeasurer,
        text = node.name.ifBlank { nodeTypeLabel(node.type) },
        topLeft = Offset(topLeft.x + 12.dp.toPx(), topLeft.y + 10.dp.toPx()),
        style = titleStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        size = Size(nodeW - 24.dp.toPx(), 24.dp.toPx()),
    )
    drawText(
        textMeasurer = textMeasurer,
        text = nodeTypeLabel(node.type),
        topLeft = Offset(topLeft.x + 12.dp.toPx(), topLeft.y + 36.dp.toPx()),
        style = subStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        size = Size(nodeW - 24.dp.toPx(), 20.dp.toPx()),
    )
}

private fun DrawScope.drawPorts(
    node: WorkflowNode,
    topLeft: Offset,
    nodeW: Float,
    nodeH: Float,
    portRadius: Float,
    portColor: Color,
    hovered: Boolean = false,
) {
    val highlightColor = if (hovered) Color(0xFFFFB300) else portColor
    drawCircle(
        color = highlightColor,
        radius = if (hovered) portRadius * 1.6f else portRadius,
        center = Offset(topLeft.x, topLeft.y + nodeH / 2f),
        style = Stroke(width = if (hovered) 3.dp.toPx() else 2.dp.toPx()),
    )
    val outs = outputPorts(node)
    outs.forEachIndexed { index, _ ->
        val y = if (outs.size == 1) nodeH / 2f else nodeH / 4f + index * nodeH / 2f
        drawCircle(
            color = highlightColor,
            radius = if (hovered) portRadius * 1.6f else portRadius,
            center = Offset(topLeft.x + nodeW, topLeft.y + y),
            style = Stroke(width = if (hovered) 3.dp.toPx() else 2.dp.toPx()),
        )
    }
}

private fun DrawScope.drawMinimap(
    graph: WorkflowGraph,
    nodeW: Float,
    nodeH: Float,
    scale: Float,
    offset: Offset,
    canvasSize: Size,
    nodeColor: Color,
    viewportColor: Color,
) {
    if (graph.nodes.isEmpty()) return
    val mapW = 120.dp.toPx()
    val mapH = 80.dp.toPx()
    val mapLeft = canvasSize.width - mapW - 12.dp.toPx()
    val mapTop = canvasSize.height - mapH - 12.dp.toPx()
    drawRoundRect(
        color = Color(0xE0000000),
        topLeft = Offset(mapLeft, mapTop),
        size = Size(mapW, mapH),
        cornerRadius = CornerRadius(8.dp.toPx()),
    )
    val xs = graph.nodes.map { it.x }
    val ys = graph.nodes.map { it.y }
    val minX = xs.min()
    val maxX = xs.max() + nodeW
    val minY = ys.min()
    val maxY = ys.max() + nodeH
    val pad = 8.dp.toPx()
    val contentW = maxX - minX
    val contentH = maxY - minY
    val scaleX = if (contentW > 0) (mapW - pad * 2) / contentW else 1f
    val scaleY = if (contentH > 0) (mapH - pad * 2) / contentH else 1f
    fun project(world: Offset): Offset {
        val dx = if (contentW > 0) (world.x - minX) * scaleX else (mapW - pad * 2) / 2f
        val dy = if (contentH > 0) (world.y - minY) * scaleY else (mapH - pad * 2) / 2f
        return Offset(mapLeft + pad + dx, mapTop + pad + dy)
    }
    for (node in graph.nodes) {
        val p = project(Offset(node.x, node.y))
        drawRect(
            color = nodeColor.copy(alpha = 0.7f),
            topLeft = p,
            size = Size((nodeW * scaleX).coerceAtLeast(4f), (nodeH * scaleY).coerceAtLeast(4f)),
        )
    }
    val vpTopLeft = project(Offset((-offset.x) / scale, (-offset.y) / scale))
    val vpSize = Size((canvasSize.width / scale) * scaleX, (canvasSize.height / scale) * scaleY)
    drawRect(
        color = viewportColor,
        topLeft = vpTopLeft,
        size = vpSize,
        style = Stroke(width = 1.dp.toPx()),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddNodeSheet(
    onDismiss: () -> Unit,
    onSelect: (NodeType) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("添加节点", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { NodeTypeRow(NodeType.START, "开始", "流程入口", { onSelect(NodeType.START) }) }
                item { NodeTypeRow(NodeType.TEXT, "文本", "输出固定内容", { onSelect(NodeType.TEXT) }) }
                item { NodeTypeRow(NodeType.AI, "AI 生成", "调用模型生成文本", { onSelect(NodeType.AI) }) }
                item { NodeTypeRow(NodeType.SHELL, "命令", "执行 shell 命令", { onSelect(NodeType.SHELL) }) }
                item { NodeTypeRow(NodeType.HTTP, "HTTP 请求", "发送网络请求", { onSelect(NodeType.HTTP) }) }
                item { NodeTypeRow(NodeType.IF, "条件分支", "按条件走 true/false 分支", { onSelect(NodeType.IF) }) }
                item { NodeTypeRow(NodeType.FOR, "循环", "逐项批量处理", { onSelect(NodeType.FOR) }) }
                item { NodeTypeRow(NodeType.MERGE, "汇聚", "合并多分支输出", { onSelect(NodeType.MERGE) }) }
                item { NodeTypeRow(NodeType.EXTRACT, "提取", "正则/JSON 提取上游输出", { onSelect(NodeType.EXTRACT) }) }
                item { NodeTypeRow(NodeType.DELAY, "延迟", "等待指定时间", { onSelect(NodeType.DELAY) }) }
                item { NodeTypeRow(NodeType.OUTPUT, "输出", "渲染最终结果", { onSelect(NodeType.OUTPUT) }) }
                item { NodeTypeRow(NodeType.END, "结束", "流程出口", { onSelect(NodeType.END) }) }
            }
        }
    }
}

@Composable
private fun NodeTypeRow(
    type: NodeType,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = nodeTypeIcon(type),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeEditSheet(
    node: WorkflowNode,
    graph: WorkflowGraph,
    onDismiss: () -> Unit,
    onUpdate: (String, StepConfig) -> Unit,
    onRemove: () -> Unit,
    onRemoveEdge: (String) -> Unit,
    onUpdateEdgeCondition: (String, String?) -> Unit,
) {
    var editingEdge by remember { mutableStateOf<WorkflowEdge?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(node.name.ifBlank { nodeTypeLabel(node.type) }, style = MaterialTheme.typography.titleMedium)
            NodeConfigEditor(node = node, onUpdate = onUpdate, onRemove = onRemove)
            val relatedEdges = graph.edges.filter { it.fromNodeId == node.id || it.toNodeId == node.id }
            if (relatedEdges.isNotEmpty()) {
                Text("连线", style = MaterialTheme.typography.labelMedium)
                relatedEdges.forEach { edge ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { editingEdge = edge }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${graph.nodes.find { it.id == edge.fromNodeId }?.name.orEmpty()} → ${graph.nodes.find { it.id == edge.toNodeId }?.name.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = edgeConditionLabel(edge.condition),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (edge.condition.isNullOrBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                        IconButton(onClick = { onRemoveEdge(edge.id) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = "删除连线",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }

    val edgeToEdit = editingEdge
    if (edgeToEdit != null) {
        EdgeConditionDialog(
            fromName = graph.nodes.find { it.id == edgeToEdit.fromNodeId }?.name.orEmpty(),
            toName = graph.nodes.find { it.id == edgeToEdit.toNodeId }?.name.orEmpty(),
            initialCondition = edgeToEdit.condition,
            onConfirm = { condition ->
                onUpdateEdgeCondition(edgeToEdit.id, condition)
                editingEdge = null
            },
            onDismiss = { editingEdge = null },
        )
    }
}

@Composable
private fun edgeConditionLabel(condition: String?): String {
    val c = condition?.trim().orEmpty()
    if (c.isBlank()) return "无条件（成功时传递）"
    return when (c.lowercase()) {
        "success", "ok", "on_success" -> "成功时执行"
        "error", "failed", "on_error" -> "失败时执行（错误处理）"
        "true" -> "结果为 true 时执行"
        "false" -> "结果为 false 时执行"
        else -> "匹配正则：$c"
    }
}

@Composable
private fun EdgeConditionDialog(
    fromName: String,
    toName: String,
    initialCondition: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = initialCondition?.trim().orEmpty()
    val initialMode = when {
        initial.isBlank() -> EdgeConditionMode.DEFAULT
        initial.equals("success", true) || initial.equals("ok", true) || initial.equals("on_success", true) ->
            EdgeConditionMode.SUCCESS
        initial.equals("error", true) || initial.equals("failed", true) || initial.equals("on_error", true) ->
            EdgeConditionMode.ERROR
        initial.equals("true", true) -> EdgeConditionMode.TRUE
        initial.equals("false", true) -> EdgeConditionMode.FALSE
        else -> EdgeConditionMode.CUSTOM
    }
    var mode by remember { mutableStateOf(initialMode) }
    var custom by remember { mutableStateOf(if (initialMode == EdgeConditionMode.CUSTOM) initial else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("连线条件")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$fromName → $toName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EdgeConditionOption(
                    selected = mode == EdgeConditionMode.DEFAULT,
                    title = "默认",
                    subtitle = "源节点成功后传递",
                    onClick = { mode = EdgeConditionMode.DEFAULT },
                )
                EdgeConditionOption(
                    selected = mode == EdgeConditionMode.SUCCESS,
                    title = "成功",
                    subtitle = "源节点成功才执行",
                    onClick = { mode = EdgeConditionMode.SUCCESS },
                )
                EdgeConditionOption(
                    selected = mode == EdgeConditionMode.ERROR,
                    title = "失败（错误处理）",
                    subtitle = "源节点失败时执行，用于补救错误",
                    onClick = { mode = EdgeConditionMode.ERROR },
                )
                EdgeConditionOption(
                    selected = mode == EdgeConditionMode.TRUE,
                    title = "true",
                    subtitle = "源输出为 true 时执行",
                    onClick = { mode = EdgeConditionMode.TRUE },
                )
                EdgeConditionOption(
                    selected = mode == EdgeConditionMode.FALSE,
                    title = "false",
                    subtitle = "源输出为 false 时执行",
                    onClick = { mode = EdgeConditionMode.FALSE },
                )
                EdgeConditionOption(
                    selected = mode == EdgeConditionMode.CUSTOM,
                    title = "自定义正则",
                    subtitle = "匹配源输出的正则表达式",
                    onClick = { mode = EdgeConditionMode.CUSTOM },
                )
                if (mode == EdgeConditionMode.CUSTOM) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("正则表达式") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        when (mode) {
                            EdgeConditionMode.DEFAULT -> null
                            EdgeConditionMode.SUCCESS -> "success"
                            EdgeConditionMode.ERROR -> "error"
                            EdgeConditionMode.TRUE -> "true"
                            EdgeConditionMode.FALSE -> "false"
                            EdgeConditionMode.CUSTOM -> custom.trim().ifBlank { null }
                        }
                    )
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private enum class EdgeConditionMode { DEFAULT, SUCCESS, ERROR, TRUE, FALSE, CUSTOM }

@Composable
private fun EdgeConditionOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                HugeIcons.Flowchart02,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun NodeConfigEditor(
    node: WorkflowNode,
    onUpdate: (String, StepConfig) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var name by rememberSaveable(node.id, node.name) { mutableStateOf(node.name) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("节点名称") },
            singleLine = true,
        )

        when (val config = node.config) {
            is TextStepConfig -> TextConfigEditor(config, node.id) { onUpdate(name, it) }
            is AiStepConfig -> AiConfigEditor(config, node.id) { onUpdate(name, it) }
            is ShellStepConfig -> ShellConfigEditor(config, node.id) { onUpdate(name, it) }
            is HttpStepConfig -> HttpConfigEditor(config, node.id) { onUpdate(name, it) }
            is DelayStepConfig -> DelayConfigEditor(config, node.id) { onUpdate(name, it) }
            is IfStepConfig -> IfConfigEditor(config, node.id) { onUpdate(name, it) }
            is ForStepConfig -> ForConfigEditor(config, node.id) { onUpdate(name, it) }
            is ExtractStepConfig -> ExtractConfigEditor(config, node.id) { onUpdate(name, it) }
            is OutputStepConfig -> OutputConfigEditor(config, node.id) { onUpdate(name, it) }
            is StartStepConfig -> NoConfigHint("开始节点无需配置")
            is EndStepConfig -> NoConfigHint("结束节点无需配置")
            is MergeStepConfig -> NoConfigHint("汇聚节点自动合并上游输出，无需配置")
        }

        TextButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(HugeIcons.Delete01, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(4.dp))
            Text("删除节点", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun NoConfigHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun TextConfigEditor(
    config: TextStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var content by rememberSaveable(name, config.content) { mutableStateOf(config.content) }
    OutlinedTextField(
        value = content,
        onValueChange = {
            content = it
            onChange(config.copy(content = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("输出内容") },
        supportingText = { Text("支持 {{node.<id>.output}} 与 {{input.NAME}} 变量") },
        minLines = 2,
    )
}

@Composable
private fun AiConfigEditor(
    config: AiStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var prompt by rememberSaveable(name, config.prompt) { mutableStateOf(config.prompt) }
    OutlinedTextField(
        value = prompt,
        onValueChange = {
            prompt = it
            onChange(config.copy(prompt = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("提示词") },
        supportingText = { Text("支持 {{node.<id>.output}} 与 {{input.NAME}} 变量") },
        minLines = 3,
    )
}

@Composable
private fun ShellConfigEditor(
    config: ShellStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var command by rememberSaveable(name, config.command) { mutableStateOf(config.command) }
    OutlinedTextField(
        value = command,
        onValueChange = {
            command = it
            onChange(config.copy(command = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("命令") },
        minLines = 2,
    )
}

@Composable
private fun HttpConfigEditor(
    config: HttpStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var method by rememberSaveable(name, config.method) { mutableStateOf(config.method) }
    var url by rememberSaveable(name, config.url) { mutableStateOf(config.url) }
    var body by rememberSaveable(name, config.body) { mutableStateOf(config.body) }
    var headersText by rememberSaveable(name, config.headers.toHeaderText()) {
        mutableStateOf(config.headers.toHeaderText())
    }

    OutlinedTextField(
        value = url,
        onValueChange = {
            url = it
            onChange(config.copy(url = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("URL") },
        singleLine = true,
    )
    OutlinedTextField(
        value = method,
        onValueChange = {
            method = it
            onChange(config.copy(method = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("方法 (GET/POST/PUT/DELETE)") },
        singleLine = true,
    )
    OutlinedTextField(
        value = headersText,
        onValueChange = {
            headersText = it
            onChange(config.copy(headers = it.parseHeaders()))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("请求头（每行一个 Key: Value）") },
        minLines = 2,
    )
    OutlinedTextField(
        value = body,
        onValueChange = {
            body = it
            onChange(config.copy(body = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("请求体（可选）") },
        minLines = 2,
    )
}

@Composable
private fun DelayConfigEditor(
    config: DelayStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var seconds by rememberSaveable(name, config.seconds) { mutableStateOf(config.seconds.toString()) }
    OutlinedTextField(
        value = seconds,
        onValueChange = {
            seconds = it
            onChange(config.copy(seconds = it.toIntOrNull() ?: 1))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("等待秒数") },
        singleLine = true,
    )
}

@Composable
private fun IfConfigEditor(
    config: IfStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var condition by rememberSaveable(name, config.condition) { mutableStateOf(config.condition) }
    OutlinedTextField(
        value = condition,
        onValueChange = {
            condition = it
            onChange(config.copy(condition = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("条件表达式") },
        supportingText = {
            Text("如 {{node.x.output}} > 1000 或 {{input.mode}} == \"fast\"。true 分支从上方输出端口连出，false 分支从下方输出端口连出")
        },
        minLines = 2,
    )
}

@Composable
private fun ForConfigEditor(
    config: ForStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var itemsSource by rememberSaveable(name, config.itemsSource) { mutableStateOf(config.itemsSource) }
    var prompt by rememberSaveable(name, config.prompt) { mutableStateOf(config.prompt) }
    var assistantId by rememberSaveable(name, config.assistantId) { mutableStateOf(config.assistantId) }
    OutlinedTextField(
        value = itemsSource,
        onValueChange = {
            itemsSource = it
            onChange(config.copy(itemsSource = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("数据源") },
        supportingText = { Text("JSON 数组或每行一项；可用 {{node.<id>.output}} 引用上游输出") },
        singleLine = true,
    )
    OutlinedTextField(
        value = prompt,
        onValueChange = {
            prompt = it
            onChange(config.copy(prompt = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("每项提示词") },
        supportingText = { Text("用 {{item}} 引用当前项、{{index}} 引用序号") },
        minLines = 2,
    )
    OutlinedTextField(
        value = assistantId,
        onValueChange = {
            assistantId = it
            onChange(config.copy(assistantId = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("助手 ID（留空用默认模型）") },
        singleLine = true,
    )
}

@Composable
private fun ExtractConfigEditor(
    config: ExtractStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var modeText by rememberSaveable(name, config.mode.name) { mutableStateOf(config.mode.name) }
    var source by rememberSaveable(name, config.source) { mutableStateOf(config.source) }
    var expression by rememberSaveable(name, config.expression) { mutableStateOf(config.expression) }
    var group by rememberSaveable(name, config.group) { mutableStateOf(config.group.toString()) }
    var defaultValue by rememberSaveable(name, config.defaultValue) { mutableStateOf(config.defaultValue) }
    var startIndex by rememberSaveable(name, config.startIndex) { mutableStateOf(config.startIndex.toString()) }
    var length by rememberSaveable(name, config.length) { mutableStateOf(config.length.toString()) }
    var others by rememberSaveable(name, config.others.joinToString("\n")) {
        mutableStateOf(config.others.joinToString("\n"))
    }
    var separator by rememberSaveable(name, config.separator) { mutableStateOf(config.separator) }

    OutlinedTextField(
        value = source,
        onValueChange = {
            source = it
            onChange(config.copy(source = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("数据来源") },
        supportingText = { Text("可用 {{node.<id>.output}} 引用上游输出") },
        singleLine = true,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("REGEX", "JSON", "SUB", "CONCAT").forEach { m ->
            FilterChip(
                selected = modeText == m,
                onClick = {
                    modeText = m
                    val mode = runCatching { ExtractMode.valueOf(m) }.getOrDefault(ExtractMode.REGEX)
                    onChange(config.copy(mode = mode))
                },
                label = { Text(m) },
            )
        }
    }

    when (runCatching { ExtractMode.valueOf(modeText) }.getOrDefault(ExtractMode.REGEX)) {
        ExtractMode.REGEX -> {
            OutlinedTextField(
                value = expression,
                onValueChange = {
                    expression = it
                    onChange(config.copy(expression = it))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("正则表达式") },
                supportingText = { Text("如 (\\d+)；匹配失败时使用默认值") },
                singleLine = true,
            )
            OutlinedTextField(
                value = group,
                onValueChange = {
                    group = it
                    onChange(config.copy(group = it.toIntOrNull() ?: 0))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("捕获组序号（默认 0 表示整体）") },
                singleLine = true,
            )
        }
        ExtractMode.JSON -> {
            OutlinedTextField(
                value = expression,
                onValueChange = {
                    expression = it
                    onChange(config.copy(expression = it))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("JSON 路径") },
                supportingText = { Text("如 $.items[0].name 或 $['key']") },
                singleLine = true,
            )
        }
        ExtractMode.SUB -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startIndex,
                    onValueChange = {
                        startIndex = it
                        onChange(config.copy(startIndex = it.toIntOrNull() ?: 0))
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("起始位置") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = length,
                    onValueChange = {
                        length = it
                        onChange(config.copy(length = it.toIntOrNull() ?: 0))
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("长度（0 到末尾）") },
                    singleLine = true,
                )
            }
        }
        ExtractMode.CONCAT -> {
            OutlinedTextField(
                value = others,
                onValueChange = {
                    others = it
                    onChange(config.copy(others = it.lines().filter { line -> line.isNotBlank() }))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("拼接内容") },
                supportingText = { Text("每行一项，与 source 一起用分隔符拼接") },
                minLines = 2,
            )
            OutlinedTextField(
                value = separator,
                onValueChange = {
                    separator = it
                    onChange(config.copy(separator = it))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("分隔符") },
                singleLine = true,
            )
        }
    }

    OutlinedTextField(
        value = defaultValue,
        onValueChange = {
            defaultValue = it
            onChange(config.copy(defaultValue = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("默认值（提取失败时使用）") },
        singleLine = true,
    )
}

@Composable
private fun OutputConfigEditor(
    config: OutputStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var template by rememberSaveable(name, config.template) { mutableStateOf(config.template) }
    OutlinedTextField(
        value = template,
        onValueChange = {
            template = it
            onChange(config.copy(template = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("输出模板") },
        supportingText = { Text("渲染结果作为整个工作流的输出") },
        minLines = 2,
    )
}

@Composable
private fun RunSummaryBar(
    succeeded: Boolean,
    progress: List<RunProgress>,
    running: Boolean,
    logs: List<WorkflowRunLogEntry>,
    error: String,
    onDismiss: () -> Unit,
) {
    var showLogs by remember { mutableStateOf(false) }
    val statusText = when {
        running -> "运行中..."
        succeeded -> "运行完成"
        else -> "运行失败"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (running) MaterialTheme.colorScheme.surfaceVariant
                else if (succeeded) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = "$statusText（成功 ${progress.count { it.status == StepStatus.SUCCESS }}/${progress.size}）",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showLogs = true }) {
                Text("日志")
            }
            if (!running) {
                TextButton(onClick = onDismiss) {
                    Text("清除")
                }
            }
        }
        if (!running && error.isNotBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("执行日志") },
            text = {
                if (logs.isEmpty()) {
                    Text("暂无日志", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier.height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(logs.size) { index ->
                            val entry = logs[index]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "${entry.level.name.lowercase()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (entry.level) {
                                        WorkflowLogLevel.ERROR -> MaterialTheme.colorScheme.error
                                        WorkflowLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                        WorkflowLogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.width(40.dp),
                                )
                                Column {
                                    if (entry.nodeName != null) {
                                        Text(
                                            text = entry.nodeName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(
                                        text = entry.message,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogs = false }) {
                    Text("关闭")
                }
            },
        )
    }
}

private fun nodeTypeIcon(type: NodeType): ImageVector = when (type) {
    NodeType.START -> HugeIcons.StartUp01
    NodeType.END -> HugeIcons.SquareLock02
    NodeType.TEXT -> HugeIcons.AlignLeft
    NodeType.AI -> HugeIcons.Sparkles
    NodeType.SHELL -> HugeIcons.CommandLine
    NodeType.HTTP -> HugeIcons.Globe
    NodeType.DELAY -> HugeIcons.Clock01
    NodeType.IF -> HugeIcons.GitBranch
    NodeType.FOR -> HugeIcons.Repeat
    NodeType.MERGE -> HugeIcons.GitMerge
    NodeType.EXTRACT -> HugeIcons.Flowchart02
    NodeType.OUTPUT -> HugeIcons.Flowchart02
}

private fun nodeTypeLabel(type: NodeType): String = when (type) {
    NodeType.START -> "开始"
    NodeType.END -> "结束"
    NodeType.TEXT -> "文本"
    NodeType.AI -> "AI 生成"
    NodeType.SHELL -> "命令"
    NodeType.HTTP -> "HTTP 请求"
    NodeType.DELAY -> "延迟"
    NodeType.IF -> "条件分支"
    NodeType.FOR -> "循环"
    NodeType.MERGE -> "汇聚"
    NodeType.EXTRACT -> "提取"
    NodeType.OUTPUT -> "输出"
}

private fun Map<String, String>.toHeaderText(): String =
    entries.joinToString("\n") { "${it.key}: ${it.value}" }

private fun String.parseHeaders(): Map<String, String> =
    lines()
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty()) key to value else null
            } else null
        }
        .toMap()
