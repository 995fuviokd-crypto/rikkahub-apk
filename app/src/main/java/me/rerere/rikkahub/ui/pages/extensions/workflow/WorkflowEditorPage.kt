package me.rerere.rikkahub.ui.pages.extensions.workflow

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.SquareLock02
import me.rerere.hugeicons.stroke.StartUp01
import me.rerere.rikkahub.data.ai.workflow.RunProgress
import me.rerere.rikkahub.data.ai.workflow.StepStatus
import me.rerere.rikkahub.data.model.AiStepConfig
import me.rerere.rikkahub.data.model.DelayStepConfig
import me.rerere.rikkahub.data.model.EndStepConfig
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
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowNode
import me.rerere.rikkahub.data.model.validate
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
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
    val selectedNodeId by vm.selectedNodeId.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddNodeSheet by remember { mutableStateOf(false) }
    val graph = workflow?.effectiveGraph

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(workflow?.name ?: "工作流") },
                navigationIcon = { BackButton() },
                actions = {
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
                    selectedNodeId = selectedNodeId,
                    onSelectNode = vm::selectNode,
                    onMoveNode = vm::moveNode,
                    onAddEdge = vm::addEdge,
                    onDeselectNode = { vm.selectNode(null) },
                )
                if (runSucceeded != null) {
                    RunSummaryBar(
                        succeeded = runSucceeded == true,
                        progress = runProgress,
                        running = running,
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
                onDismiss = { vm.selectNode(null) },
                onUpdate = { name, config -> vm.updateNode(node.id, name, config) },
                onRemove = { vm.removeNode(node.id) },
                onRemoveEdge = vm::removeEdge,
            )
        } else {
            vm.selectNode(null)
        }
    }
}

@Composable
private fun WorkflowGraphCanvas(
    graph: WorkflowGraph,
    runProgress: List<RunProgress>,
    selectedNodeId: String?,
    onSelectNode: (String?) -> Unit,
    onMoveNode: (String, Float, Float) -> Unit,
    onAddEdge: (String, String, String) -> Boolean,
    onDeselectNode: () -> Unit,
) {
    val density = LocalDensity.current
    val nodeW = with(density) { 180.dp.toPx() }
    val nodeH = with(density) { 72.dp.toPx() }
    val portRadius = with(density) { 7.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()

    val scaleState = remember { mutableFloatStateOf(1f) }
    val offsetState = remember { mutableStateOf(Offset(40f, 40f)) }
    var dragOverride by remember { mutableStateOf<Pair<String, Offset>?>(null) }

    val progressById = runProgress.associateBy { it.nodeId }
    val colorScheme = MaterialTheme.colorScheme
    val titleStyle = MaterialTheme.typography.bodyMedium.copy(color = colorScheme.onSurface)
    val subStyle = MaterialTheme.typography.labelSmall.copy(color = colorScheme.onSurfaceVariant)

    fun nodeScreen(node: WorkflowNode): Offset {
        val o = dragOverride
        val world = if (o?.first == node.id) o.second else Offset(node.x, node.y)
        return Offset(world.x * scaleState.floatValue + offsetState.value.x, world.y * scaleState.floatValue + offsetState.value.y)
    }

    fun portWorld(node: WorkflowNode, port: String): Offset {
        val base = if (dragOverride?.first == node.id) dragOverride!!.second else Offset(node.x, node.y)
        return when (port) {
            "in" -> base + Offset(0f, nodeH / 2)
            "true" -> base + Offset(nodeW, nodeH / 4)
            "false" -> base + Offset(nodeW, nodeH * 3 / 4)
            else -> base + Offset(nodeW, nodeH / 2)
        }
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

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(graph) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val downScreen = down.position
                    val (hitNode, hitPort) = hitTest(downScreen)
                    var mode: GestureMode = GestureMode.NONE
                    var dragNodeId: String? = null
                    var dragAccum = Offset.Zero
                    if (hitPort != null) {
                        mode = GestureMode.LINK
                    } else if (hitNode != null) {
                        mode = GestureMode.DRAG_NODE
                        dragNodeId = hitNode.id
                        onSelectNode(hitNode.id)
                    } else {
                        mode = GestureMode.PAN
                        onDeselectNode()
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
                            val p1 = event.changes[0].position
                            val p2 = event.changes[1].position
                            val prevP1 = event.changes[0].previousPosition
                            val prevP2 = event.changes[1].previousPosition
                            val newDist = (p2 - p1).getDistance()
                            val oldDist = (prevP2 - prevP1).getDistance()
                            if (oldDist > 0f && newDist > 0f) {
                                val newScale = (scaleState.floatValue * newDist / oldDist).coerceIn(0.3f, 3f)
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
                                GestureMode.PAN -> offsetState.value += changed.positionChange()
                                GestureMode.DRAG_NODE -> {
                                    val node = hitNode ?: break
                                    val delta = changed.positionChange() / scaleState.floatValue
                                    dragAccum += delta
                                    dragOverride = node.id to Offset(
                                        snap(node.x + dragAccum.x),
                                        snap(node.y + dragAccum.y),
                                    )
                                }
                                GestureMode.LINK -> Unit
                                GestureMode.NONE -> Unit
                            }
                        }
                        changed.consume()
                        if (event.changes.all { it.changedToUp() }) break
                    }
                    when (mode) {
                        GestureMode.DRAG_NODE -> {
                            val node = hitNode
                            if (node != null) {
                                onMoveNode(node.id, snap(node.x + dragAccum.x), snap(node.y + dragAccum.y))
                            }
                            dragOverride = null
                        }
                        GestureMode.LINK -> {
                            val up = lastEvent
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
            drawEdge(startScreen, endScreen, edgeColor(progressById[to.id]?.status))
        }

        for (node in graph.nodes) {
            val p = nodeScreen(node)
            val status = progressById[node.id]?.status
            val isSelected = node.id == selectedNodeId
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
            drawPorts(node, p, nodeW, nodeH, portRadius, edgeColor(status))
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
}

private enum class GestureMode { NONE, PAN, DRAG_NODE, LINK }

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

private fun DrawScope.drawEdge(start: Offset, end: Offset, color: Color) {
    val midX = (start.x + end.x) / 2f
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(midX, start.y, midX, end.y, end.x, end.y)
    }
    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    drawCircle(color, radius = 3.dp.toPx(), center = end)
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
) {
    drawCircle(
        color = portColor,
        radius = portRadius,
        center = Offset(topLeft.x, topLeft.y + nodeH / 2f),
        style = Stroke(width = 2.dp.toPx()),
    )
    val outs = outputPorts(node)
    outs.forEachIndexed { index, _ ->
        val y = if (outs.size == 1) nodeH / 2f else nodeH / 4f + index * nodeH / 2f
        drawCircle(
            color = portColor,
            radius = portRadius,
            center = Offset(topLeft.x + nodeW, topLeft.y + y),
            style = Stroke(width = 2.dp.toPx()),
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
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
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${graph.nodes.find { it.id == edge.fromNodeId }?.name.orEmpty()} → ${graph.nodes.find { it.id == edge.toNodeId }?.name.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
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
    onDismiss: () -> Unit,
) {
    val statusText = when {
        running -> "运行中..."
        succeeded -> "运行完成"
        else -> "运行失败，已终止"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (running) MaterialTheme.colorScheme.surfaceVariant
                else if (succeeded) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "$statusText（成功 ${progress.count { it.status == StepStatus.SUCCESS }}/${progress.size}）",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.weight(1f))
        if (!running) {
            TextButton(onClick = onDismiss) {
                Text("清除")
            }
        }
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
