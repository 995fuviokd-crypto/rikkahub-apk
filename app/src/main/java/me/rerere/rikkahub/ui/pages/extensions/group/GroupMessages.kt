package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain01
import me.rerere.hugeicons.stroke.Chat01
import me.rerere.hugeicons.stroke.CheckmarkSquare01
import me.rerere.hugeicons.stroke.ListView
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.data.ai.group.GroupRunner
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupToolRecord
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.JsonInstant
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
            GroupMessageExtras(message)
        }
    }
}

@Composable
private fun GroupMessageExtras(message: GroupMessage) {
    val reasoning = message.reasoning
    val tools = parseTools(message.tools)
    if (reasoning.isBlank() && tools.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (reasoning.isNotBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Surface(
                onClick = { expanded = !expanded },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Brain01,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "思考过程",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (expanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expanded) {
                        Text(
                            text = reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "工具调用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tools.forEach { tool ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Wrench01,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = tool.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (tool.output.isNotBlank()) {
                                Text(
                                    text = tool.output,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseTools(json: String): List<GroupToolRecord> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        JsonInstant.decodeFromString<List<GroupToolRecord>>(json)
    }.getOrDefault(emptyList())
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
