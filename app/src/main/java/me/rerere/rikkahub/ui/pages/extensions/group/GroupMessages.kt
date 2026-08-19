package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Chat01
import me.rerere.hugeicons.stroke.CheckmarkSquare01
import me.rerere.hugeicons.stroke.ListView
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.rikkahub.data.ai.group.GroupRunner
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.ui.theme.CustomColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GroupMessageTimeline(
    messages: List<GroupMessage>,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = HugeIcons.UserGroup,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "暂无消息",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            GroupMessageItem(message)
        }
    }
}

@Composable
fun GroupMessageItem(message: GroupMessage) {
    val isSystem = message.memberId == GroupRunner.SYSTEM_MEMBER_ID
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isSystem) {
            CustomColors.cardColorsOnSurfaceContainer
        } else {
            CustomColors.cardColorsOnSurfaceContainer
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isSystem) {
                    Icon(
                        imageVector = message.kind.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = message.kind.color(),
                    )
                }
                Text(
                    text = if (isSystem) "系统" else message.memberRole.ifBlank { "成员" },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSystem) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (message.memberModelName.isNotBlank()) {
                    Text(
                        text = message.memberModelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!isSystem) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = {
                            Text(
                                text = message.kind.label(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

fun MessageKind.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    MessageKind.PLAN -> HugeIcons.ListView
    MessageKind.SUBTASK -> HugeIcons.PencilEdit01
    MessageKind.RESULT -> HugeIcons.CheckmarkSquare01
    MessageKind.REPLY -> HugeIcons.Chat01
    MessageKind.SYSTEM -> HugeIcons.MoreVertical
}

fun MessageKind.color(): androidx.compose.ui.graphics.Color = when (this) {
    MessageKind.PLAN -> androidx.compose.ui.graphics.Color(0xFF7C4DFF)
    MessageKind.SUBTASK -> androidx.compose.ui.graphics.Color(0xFF2962FF)
    MessageKind.RESULT -> androidx.compose.ui.graphics.Color(0xFF00C853)
    MessageKind.REPLY -> androidx.compose.ui.graphics.Color(0xFFFF6D00)
    MessageKind.SYSTEM -> androidx.compose.ui.graphics.Color(0xFF546E7A)
}

fun MessageKind.label(): String = when (this) {
    MessageKind.PLAN -> "计划"
    MessageKind.SUBTASK -> "子任务"
    MessageKind.RESULT -> "结果"
    MessageKind.REPLY -> "发言"
    MessageKind.SYSTEM -> "系统"
}

fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
