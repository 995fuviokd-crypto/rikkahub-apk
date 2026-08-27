package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
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

/** 超过该字符数的成员正文在初始渲染时默认折叠，点击标题行展开 */
private const val COLLAPSE_THRESHOLD = 600

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
    val isUser = message.memberId == GroupRunner.USER_MEMBER_ID
    // 长内容（含思考过程）默认折叠；系统/用户提示通常较短则直接展示
    val longContent = message.content.length > COLLAPSE_THRESHOLD ||
        message.reasoning.length > COLLAPSE_THRESHOLD
    var expanded by rememberSaveable(message.id) {
        mutableStateOf(!longContent)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (longContent || message.reasoning.isNotBlank()) {
                            Modifier.clickable { expanded = !expanded }
                        } else {
                            Modifier
                        }
                    ),
            ) {
                Icon(
                    imageVector = message.kind.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = message.kind.color(),
                )
                Text(
                    text = when {
                        isSystem -> "系统"
                        isUser -> "用户"
                        else -> message.memberRole.ifBlank { "成员" }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSystem || isUser) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (longContent || message.reasoning.isNotBlank()) {
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (message.reasoning.isNotBlank()) {
                        Text(
                            text = message.reasoning,
                            style = MaterialTheme.typography.bodySmall,
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

            if (!expanded && longContent) {
                Text(
                    text = message.content.replace("\n", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

fun MessageKind.icon(): ImageVector = when (this) {
    MessageKind.USER -> HugeIcons.Chat01
    MessageKind.PLAN -> HugeIcons.ListView
    MessageKind.SUBTASK -> HugeIcons.PencilEdit01
    MessageKind.RESULT -> HugeIcons.CheckmarkSquare01
    MessageKind.REPLY -> HugeIcons.Chat01
    MessageKind.SYSTEM -> HugeIcons.MoreVertical
}
fun MessageKind.color(): androidx.compose.ui.graphics.Color = when (this) {
    MessageKind.USER -> androidx.compose.ui.graphics.Color(0xFF2962FF)
    MessageKind.PLAN -> androidx.compose.ui.graphics.Color(0xFF7C4DFF)
    MessageKind.SUBTASK -> androidx.compose.ui.graphics.Color(0xFF2962FF)
    MessageKind.RESULT -> androidx.compose.ui.graphics.Color(0xFF00C853)
    MessageKind.REPLY -> androidx.compose.ui.graphics.Color(0xFFFF6D00)
    MessageKind.SYSTEM -> androidx.compose.ui.graphics.Color(0xFF546E7A)
}

fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
