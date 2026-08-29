package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.TaskDone01

/** plan 工具的消息渲染器：折叠态显示最近一次计划操作，展开内联展示当前任务清单概览。 */
internal object PlanToolUI : ToolUIRenderer {
    override val toolName: String get() = "plan"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.TaskDone01

    @Composable
    override fun title(context: ToolUIContext): String {
        val action = context.arguments.getStringContent("action") ?: "plan"
        return when (action) {
            "create" -> "计划 · 新增"
            "update" -> "计划 · 更新"
            "list" -> "计划 · 查看"
            else -> "计划"
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content
        if (content == null) return
        val tasks = (content as? JsonObject)?.get("tasks") as? kotlinx.serialization.json.JsonArray
        if (tasks.isNullOrEmpty()) return
        val completed = tasks.count {
            (it as? JsonObject)?.get("status")?.let { s -> (s as? JsonPrimitive)?.contentOrNull } == "completed"
        }
        Text(
            text = "$completed/${tasks.size} 进行中",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val outputJson = context.content
        val tasks = (outputJson as? JsonObject)?.get("tasks") as? kotlinx.serialization.json.JsonArray
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "计划",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (tasks.isNullOrEmpty()) {
                Text(
                    text = "无计划条目",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                tasks.forEach { element ->
                    val task = element as? JsonObject ?: return@forEach
                    val id = task["task_id"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    val content = task["content"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    val status = task["status"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(statusColor(status)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (id.isNotBlank()) {
                                Text(
                                    text = id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun statusColor(status: String): Color = when (status) {
        "completed" -> Color(0xFF10B981)
        "in_progress" -> Color(0xFFF59E0B)
        else -> Color(0xFF9CA3AF)
    }
}
