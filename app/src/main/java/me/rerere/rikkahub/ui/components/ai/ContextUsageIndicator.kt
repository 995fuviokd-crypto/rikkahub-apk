package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.ai.subagent.SubagentRunTracker
import me.rerere.rikkahub.utils.ContextBreakdown

/**
 * 上下文容量小球：圆形进度环展示当前对话 token 占用占上下文窗口的比例。
 *
 * - 点击弹出分类占比 BottomSheet（活跃消息 / 历史摘要 / MCP 工具 / 系统工具 / 系统提示词）；
 * - 小球右下角叠加子代理计数徽章（进行中的子代理高亮显示）；
 * - 使用率超阈值时环变红。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextUsageIndicator(
    tokens: Int,
    threshold: Int,
    breakdown: ContextBreakdown.Result,
    subagentState: SubagentRunTracker.TrackerState,
    onToggleAutoCompress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showBreakdown by remember { mutableStateOf(false) }
    var showAutoCompressHint by remember { mutableStateOf(false) }

    val progress = if (threshold > 0) {
        (tokens.toFloat() / threshold).coerceIn(0f, 1f)
    } else {
        0f
    }
    val overLimit = progress >= 1f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        label = "contextProgress",
    )
    val ringColor by animateColorAsState(
        targetValue = if (overLimit) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary,
        label = "contextRingColor",
    )

    Box(
        modifier = modifier
            .size(34.dp)
            .clickable {
                if (showBreakdown) {
                    showBreakdown = false
                } else {
                    if (overLimit && onToggleAutoCompress != null) {
                        showAutoCompressHint = true
                    } else {
                        showBreakdown = true
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.size(34.dp)) {
            val strokeWidth = size.minDimension * 0.12f
            val arcSize = Size(size.minDimension - strokeWidth * 2, size.minDimension - strokeWidth * 2)
            // 背景环
            drawArc(
                color = ringColor.copy(alpha = 0.15f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(strokeWidth, strokeWidth),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            // 进度环
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = Offset(strokeWidth, strokeWidth),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "${(progress * 100).toInt()}%",
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
            ),
            textAlign = TextAlign.Center,
        )
        if (subagentState.runs.isNotEmpty()) {
            val running = subagentState.runningCount
            val badgeColor = if (running > 0) Color(0xFF10B981) else Color(0xFF9CA3AF)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .padding(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(12.dp)) {
                    drawCircle(color = badgeColor)
                }
                Text(
                    text = "${subagentState.totalCount}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showAutoCompressHint) {
        AlertDialog(
            onDismissRequest = { showAutoCompressHint = false },
            title = { Text("上下文已满") },
            text = { Text("当前会话上下文使用量已接近上限。是否启用自动压缩以继续对话？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAutoCompressHint = false
                        onToggleAutoCompress?.invoke()
                    }
                ) {
                    Text("启用自动压缩")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutoCompressHint = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showBreakdown) {
        ModalBottomSheet(onDismissRequest = { showBreakdown = false }) {
            BreakdownSheetContent(
                tokens = tokens,
                threshold = threshold,
                breakdown = breakdown,
                subagentState = subagentState,
            )
        }
    }
}

@Composable
private fun BreakdownSheetContent(
    tokens: Int,
    threshold: Int,
    breakdown: ContextBreakdown.Result,
    subagentState: SubagentRunTracker.TrackerState,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "上下文容量",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$tokens / $threshold tokens",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))

        if (subagentState.runs.isNotEmpty()) {
            Text(
                text = "子代理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                subagentState.runs.takeLast(6).forEach { run ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .padding(0.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(8.dp)) {
                                drawCircle(
                                    color = when (run.success) {
                                        true -> Color(0xFF10B981)
                                        false -> Color(0xFFEF4444)
                                        null -> Color(0xFFF59E0B)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = run.promptSummary,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = run.modelName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = run.durationMs?.let { "${it / 1000}s" } ?: "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
        }

        Text(
            text = "分类占比",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))
        val max = breakdown.max.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            breakdown.categories.forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .padding(0.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.size(10.dp)) {
                            drawCircle(color = Color(category.color))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = category.label,
                        modifier = Modifier.width(88.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .padding(0.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val width = if (category.tokens > 0) {
                            (category.tokens.toFloat() / max).coerceIn(0.02f, 1f)
                        } else {
                            0f
                        }
                        Canvas(modifier = Modifier.fillMaxWidth()) {
                            drawRoundRect(
                                color = Color(category.color).copy(alpha = 0.2f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                            )
                            if (width > 0f) {
                                drawRoundRect(
                                    color = Color(category.color),
                                    size = Size(size.width * width, size.height),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = category.tokens.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}
