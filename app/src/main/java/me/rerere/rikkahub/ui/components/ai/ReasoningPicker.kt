package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Fire
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface

private val levels = ReasoningLevel.entries

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    var showPicker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningLevel = reasoningLevel,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningLevel = onUpdateReasoningLevel
        )
    }

    ToggleSurface(
        checked = reasoningLevel.isEnabled,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                vertical = if (onlyIcon) 3.dp else 8.dp,
                horizontal = if (onlyIcon) 3.dp else 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                ReasoningIcon(reasoningLevel)
            }
            if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
        }
    }
}

/**
 * Codex 风格思考强度选择器：分段胶囊条 + 当前档位说明。
 * 每档一个胶囊，选中的实心主色白字并轻微放大，未选中为表面色；
 * 胶囊下方展示当前档位的一句话说明。
 */
@Composable
fun ReasoningPicker(
    reasoningLevel: ReasoningLevel,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 标题 + 提示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.reasoning_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.reasoning_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // 分段胶囊条
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                levels.forEach { level ->
                    ReasoningSegment(
                        level = level,
                        selected = level == reasoningLevel,
                        onClick = { onUpdateReasoningLevel(level) },
                    )
                }
            }

            // 当前档位说明
            Text(
                text = reasoningLevel.descriptionRes(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReasoningSegment(
    level: ReasoningLevel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = spring(stiffness = 600f),
        label = "segmentContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(stiffness = 600f),
        label = "segmentContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.05f else 1f,
        animationSpec = spring(stiffness = 600f),
        label = "segmentScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (selected) 2.dp else 0.dp,
        animationSpec = spring(stiffness = 600f),
        label = "segmentElevation",
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .elevationCompat(elevation)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val showIcon = level != ReasoningLevel.OFF || selected
            if (showIcon) {
                Icon(
                    imageVector = level.icon(),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = level.label(),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
        }
    }
}

private fun Modifier.elevationCompat(elevation: androidx.compose.ui.unit.Dp): Modifier =
    this.then(
        Modifier.graphicsLayer {
            shadowElevation = elevation.toPx()
            shape = RoundedCornerShape(50)
            clip = false
        }
    )

@Composable
private fun ReasoningLevel.label(): String = stringResource(
    when (this) {
        ReasoningLevel.OFF -> R.string.reasoning_off
        ReasoningLevel.AUTO -> R.string.reasoning_auto
        ReasoningLevel.LOW -> R.string.reasoning_light
        ReasoningLevel.MEDIUM -> R.string.reasoning_medium
        ReasoningLevel.HIGH -> R.string.reasoning_heavy
        ReasoningLevel.XHIGH -> R.string.reasoning_xhigh
        ReasoningLevel.MAX -> R.string.reasoning_max
    }
)

@Composable
private fun ReasoningLevel.descriptionRes(): String = stringResource(
    when (this) {
        ReasoningLevel.OFF -> R.string.reasoning_desc_off
        ReasoningLevel.AUTO -> R.string.reasoning_desc_auto
        ReasoningLevel.LOW -> R.string.reasoning_desc_low
        ReasoningLevel.MEDIUM -> R.string.reasoning_desc_medium
        ReasoningLevel.HIGH -> R.string.reasoning_desc_high
        ReasoningLevel.XHIGH -> R.string.reasoning_desc_xhigh
        ReasoningLevel.MAX -> R.string.reasoning_desc_max
    }
)

@Composable
private fun ReasoningLevel.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    ReasoningLevel.OFF -> HugeIcons.Idea
    ReasoningLevel.AUTO -> HugeIcons.Idea01
    ReasoningLevel.LOW -> HugeIcons.Flash
    ReasoningLevel.MEDIUM -> HugeIcons.Flash
    ReasoningLevel.HIGH -> HugeIcons.Fire
    ReasoningLevel.XHIGH -> HugeIcons.Fire
    ReasoningLevel.MAX -> HugeIcons.Fire
}

@Composable
private fun ReasoningIcon(level: ReasoningLevel) {
    Icon(level.icon(), null)
}

@Composable
@Preview(showBackground = true)
private fun ReasoningPickerPreview() {
    MaterialTheme {
        var level by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ReasoningLevel.AUTO) }
        ReasoningPicker(
            reasoningLevel = level,
            onUpdateReasoningLevel = { level = it }
        )
    }
}
