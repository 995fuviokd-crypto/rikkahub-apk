package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryTarget
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 记忆图谱（无限画布）视图：
 * 记忆作为按目标类型着色的节点呈现在可平移/缩放的画布上，
 * 点击节点回调详情（由页面负责弹出详情/编辑/删除）。
 *
 * 节点采用确定性网格布局（按 id 排序），不会随重组漂移。
 */
@Composable
internal fun MemoryGraphCanvas(
    memories: List<AssistantMemory>,
    onOpenNode: (AssistantMemory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val nodeW = with(density) { 200.dp.toPx() }
    val nodeH = with(density) { 76.dp.toPx() }
    val spacingX = with(density) { 240.dp.toPx() }
    val spacingY = with(density) { 116.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()

    val colorScheme = MaterialTheme.colorScheme
    val nodeBorderColor = colorScheme.outlineVariant
    val titleColor = colorScheme.onSurface
    val bodyColor = colorScheme.onSurfaceVariant
    val gridColor = colorScheme.outlineVariant.copy(alpha = 0.4f)

    // 目标类型 → 强调色（@Composable 上下文计算，避免 DrawScope 内调用 Composable）
    val targetColors = remember(memories, colorScheme) {
        memories.associate { memory ->
            val target = MemoryTarget.fromString(memory.target)
            memory.id to when (target) {
                MemoryTarget.USER -> colorScheme.primary
                MemoryTarget.MEMORY -> colorScheme.tertiary
                MemoryTarget.PROJECT -> colorScheme.secondary
                MemoryTarget.OPS -> colorScheme.error
                MemoryTarget.GENERAL -> colorScheme.outline
            }
        }
    }

    val sorted = remember(memories) {
        memories.sortedBy { it.id }
    }
    val columns = remember(sorted.size) {
        if (sorted.isEmpty()) 1 else maxOf(1, ceil(sqrt(sorted.size.toDouble())).toInt())
    }

    val gridW = ((columns - 1) * spacingX + nodeW).coerceAtLeast(1f)
    val rowCount = if (sorted.isEmpty()) 1 else (sorted.size + columns - 1) / columns
    val gridH = (((rowCount - 1).coerceAtLeast(0)) * spacingY + nodeH).coerceAtLeast(1f)

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var centered by remember { mutableStateOf(false) }

    fun layout(index: Int): Offset {
        val row = index / columns
        val col = index % columns
        return Offset(col * spacingX + nodeW / 2f - gridW / 2f, row * spacingY + nodeH / 2f - gridH / 2f)
    }

    fun nodeScreen(index: Int): Offset {
        val world = layout(index)
        return Offset(world.x * scale + offset.x, world.y * scale + offset.y)
    }

    fun hitTest(screen: Offset): Int? {
        for (idx in sorted.indices.reversed()) {
            val p = nodeScreen(idx)
            val rect = Rect(p.x, p.y, p.x + nodeW, p.y + nodeH)
            if (rect.contains(screen)) return idx
        }
        return null
    }

    LaunchedEffect(canvasSize, centered) {
        if (!centered && canvasSize.width > 0 && canvasSize.height > 0) {
            scale = 1f
            offset = Offset(
                (canvasSize.width - gridW) / 2f + gridW / 2f,
                (canvasSize.height - gridH) / 2f + gridH / 2f,
            )
            centered = true
        }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(sorted.size) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var panning = false
                        var tapCandidate = true
                        var lastEvent: PointerInputChange? = null
                        var wasMultiTouch = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val changed = event.changes.firstOrNull() ?: break
                            lastEvent = changed
                            if (event.changes.size >= 2) {
                                panning = false
                                tapCandidate = false
                                wasMultiTouch = true
                                val p1 = event.changes[0].position
                                val p2 = event.changes[1].position
                                val prevP1 = event.changes[0].previousPosition
                                val prevP2 = event.changes[1].previousPosition
                                val newDist = (p2 - p1).getDistance()
                                val oldDist = (prevP2 - prevP1).getDistance()
                                if (oldDist > 0f && newDist > 0f) {
                                    val newScale = (scale * newDist / oldDist).coerceIn(0.2f, 3f)
                                    val centroid = (p1 + p2) / 2f
                                    val prevCentroid = (prevP1 + prevP2) / 2f
                                    offset = centroid - (centroid - offset) * (newScale / scale)
                                    scale = newScale
                                }
                                offset += (p1 + p2) / 2f - (prevP1 + prevP2) / 2f
                            } else {
                                if (wasMultiTouch) {
                                    wasMultiTouch = false
                                }
                                val delta = changed.positionChange()
                                if (panning || delta.getDistance() > 3f) {
                                    panning = true
                                    tapCandidate = false
                                    offset += delta
                                }
                            }
                            changed.consume()
                            if (event.changes.all { it.changedToUp() }) break
                        }
                        if (tapCandidate && lastEvent != null && !wasMultiTouch) {
                            val up = lastEvent
                            val hit = hitTest(up.position)
                            if (hit != null) {
                                onOpenNode(sorted[hit])
                            }
                        }
                    }
                },
        ) {
            drawGridBackground(scale, offset, gridColor)

            for (idx in sorted.indices) {
                val memory = sorted[idx]
                val p = nodeScreen(idx)
                drawMemoryNode(
                    memory = memory,
                    topLeft = p,
                    w = nodeW,
                    h = nodeH,
                    borderColor = nodeBorderColor,
                    accentColor = targetColors[memory.id] ?: nodeBorderColor,
                    titleStyle = TextStyle(
                        color = titleColor,
                        fontSize = 12.sp,
                    ),
                    bodyStyle = TextStyle(
                        color = bodyColor,
                        fontSize = 10.sp,
                    ),
                    fillColor = colorScheme.surface,
                    textMeasurer = textMeasurer,
                )
            }
        }
    }
}

private fun DrawScope.drawGridBackground(scale: Float, offset: Offset, gridColor: Color) {
    val step = 80f * scale
    if (step < 12f) return
    var x = (offset.x % step).let { if (it < 0) it + step else it }
    while (x < size.width) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = (offset.y % step).let { if (it < 0) it + step else it }
    while (y < size.height) {
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

private fun DrawScope.drawMemoryNode(
    memory: AssistantMemory,
    topLeft: Offset,
    w: Float,
    h: Float,
    borderColor: Color,
    accentColor: Color,
    titleStyle: TextStyle,
    bodyStyle: TextStyle,
    fillColor: Color,
    textMeasurer: TextMeasurer,
) {
    val radius = CornerRadius(8f)
    drawRoundRect(color = fillColor, topLeft = topLeft, size = Size(w, h), cornerRadius = radius)
    drawRoundRect(
        color = borderColor,
        topLeft = topLeft,
        size = Size(w, h),
        cornerRadius = radius,
        style = Stroke(width = 1.5f),
    )
    drawRoundRect(
        color = accentColor,
        topLeft = Offset(topLeft.x, topLeft.y),
        size = Size(5f, h),
        cornerRadius = CornerRadius(8f, 0f),
    )

    val title = memory.summary?.takeIf { it.isNotBlank() }?.let { "摘要 · $it" } ?: "记忆 #${memory.id}"
    val content = memory.content.replace("\n", " ")

    drawText(
        textMeasurer = textMeasurer,
        text = title,
        topLeft = Offset(topLeft.x + 14f, topLeft.y + 8f),
        style = titleStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        size = Size(w - 24f, 20f),
    )
    drawText(
        textMeasurer = textMeasurer,
        text = content,
        topLeft = Offset(topLeft.x + 14f, topLeft.y + 32f),
        style = bodyStyle,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        size = Size(w - 24f, 34f),
    )
}