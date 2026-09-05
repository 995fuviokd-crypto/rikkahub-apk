package me.rerere.rikkahub.ui.schema

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import kotlinx.serialization.json.put
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * Schema 面板渲染器（design.md D2.2 schema 轨）。
 *
 * - 递归渲染 [PluginPanelComponent] 类型化组件目录，组件 key 作为 Compose key
 *   实现增量重渲染（新 schema 仅重排/更新变化节点）
 * - 未知组件类型安全降级为占位提示（不崩溃、不白屏）
 * - 交互事件（button/toggle/slider/select）经 [onEvent] 回传宿主，由宿主
 *   调用插件脚本 onAction 并以返回的新 schema 重渲染
 */
@Composable
fun SchemaPanelRenderer(
    components: List<PluginPanelComponent>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onEvent: (SchemaPanelEvent) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        components.forEach { component ->
            SchemaComponent(
                component = component,
                enabled = enabled,
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun SchemaComponent(
    component: PluginPanelComponent,
    enabled: Boolean,
    onEvent: (SchemaPanelEvent) -> Unit,
) {
    when (component.type) {
            PluginPanelComponent.TYPE_SECTION -> SchemaSection(component, enabled, onEvent)
            PluginPanelComponent.TYPE_CARD -> SchemaCard(component, enabled, onEvent)
            PluginPanelComponent.TYPE_TEXT -> SchemaText(component)
            PluginPanelComponent.TYPE_MARKDOWN -> SchemaMarkdown(component)
            PluginPanelComponent.TYPE_BUTTON -> SchemaButton(component, enabled, onEvent)
            PluginPanelComponent.TYPE_TOGGLE -> SchemaToggle(component, enabled, onEvent)
            PluginPanelComponent.TYPE_SLIDER -> SchemaSlider(component, enabled, onEvent)
            PluginPanelComponent.TYPE_SELECT -> SchemaSelect(component, enabled, onEvent)
            PluginPanelComponent.TYPE_LIST -> SchemaList(component, enabled, onEvent)
            PluginPanelComponent.TYPE_GRID -> SchemaGrid(component, enabled, onEvent)
            PluginPanelComponent.TYPE_PROGRESS -> SchemaProgress(component)
            PluginPanelComponent.TYPE_CHART -> SchemaChart(component)
            else -> SchemaUnknown(component)
    }
}

@Composable
private fun SchemaSection(component: PluginPanelComponent, enabled: Boolean, onEvent: (SchemaPanelEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val title = component.props.propString("title", "label")
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            component.children.forEach { child ->
                SchemaComponent(child, enabled, onEvent)
            }
        }
        if (component.children.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun SchemaCard(component: PluginPanelComponent, enabled: Boolean, onEvent: (SchemaPanelEvent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = component.props.propString("title", "label")
            val subtitle = component.props.propString("subtitle")
            if (title.isNotEmpty()) {
                Text(text = title, style = MaterialTheme.typography.titleSmallEmphasized)
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            component.children.forEach { child ->
                SchemaComponent(child, enabled, onEvent)
            }
        }
    }
}

@Composable
private fun SchemaText(component: PluginPanelComponent) {
    val text = component.props.propString("text", "value", "label")
    val style = component.props.propString("style")
    val align = component.props.propString("align")
    Text(
        text = text,
        style = when (style) {
            "title" -> MaterialTheme.typography.titleMedium
            "emphasized" -> MaterialTheme.typography.titleSmallEmphasized
            "label" -> MaterialTheme.typography.labelMedium
            "caption" -> MaterialTheme.typography.bodySmall
            else -> MaterialTheme.typography.bodyMedium
        },
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = when (align) {
            "center" -> TextAlign.Center
            "end", "right" -> TextAlign.End
            else -> TextAlign.Start
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SchemaMarkdown(component: PluginPanelComponent) {
    val text = component.props.propString("text", "content", "value")
    MarkdownBlock(content = text, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SchemaButton(component: PluginPanelComponent, enabled: Boolean, onEvent: (SchemaPanelEvent) -> Unit) {
    val label = component.props.propString("label", "text", default = component.key)
    val action = component.props.propString("action", default = "click")
    OutlinedButton(
        onClick = { onEvent(SchemaPanelEvent(component.key, action)) },
        enabled = enabled,
    ) {
        Text(label)
    }
}

@Composable
private fun SchemaToggle(component: PluginPanelComponent, enabled: Boolean, onEvent: (SchemaPanelEvent) -> Unit) {
    // 本地即时反馈 + 事件回传；真实状态以下一轮 schema 为准
    var checked by remember(component.key) {
        mutableStateOf(component.props.propBoolean("value", "checked", default = false))
    }
    val label = component.props.propString("label", "title")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onEvent(SchemaPanelEvent(component.key, "toggle", value = it.toString()))
            },
            enabled = enabled,
        )
    }
}

@Composable
private fun SchemaSlider(component: PluginPanelComponent, enabled: Boolean, onEvent: (SchemaPanelEvent) -> Unit) {
    val label = component.props.propString("label", "title")
    val min = component.props.propDouble("min", default = 0.0).toFloat()
    val max = component.props.propDouble("max", default = 100.0).toFloat().coerceAtLeast(min + 0.01f)
    var value by remember(component.key) {
        mutableStateOf(component.props.propDouble("value", default = 0.0).toFloat().coerceIn(min, max))
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatSliderValue(value.toDouble()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = {
                onEvent(SchemaPanelEvent(component.key, "slide", value = formatSliderValue(value.toDouble())))
            },
            valueRange = min..max,
            steps = component.props.propInt("steps", default = 0).coerceAtLeast(0),
            enabled = enabled,
        )
    }
}

private fun formatSliderValue(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.2f", value)

@Composable
private fun SchemaSelect(component: PluginPanelComponent, enabled: Boolean, onEvent: (SchemaPanelEvent) -> Unit) {
    val label = component.props.propString("label", "title")
    val options = component.props.propString("options")
        .split(',', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val selected = component.props.propString("value", "selected")
    Column(modifier = Modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
        if (options.isEmpty()) {
            SchemaUnknown(component)
            return
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.take(5).forEach { option ->
                val isSelected = option == selected
                AssistChip(
                    onClick = { onEvent(SchemaPanelEvent(component.key, "select", value = option)) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SchemaList(component: PluginPanelComponent, enabled: Boolean, onEvent: (SchemaPanelEvent) -> Unit) {
    // 子组件即列表项；无 children 时回退 props.items 逗号分隔
    if (component.children.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            component.children.forEach { child ->
                SchemaComponent(child, enabled, onEvent)
            }
        }
    } else {
        val items = component.props.propString("items")
            .split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (items.isEmpty()) {
            SchemaUnknown(component)
            return
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Lucide.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SchemaGrid(component: PluginPanelComponent, enabled: Boolean, onEvent: (SchemaPanelEvent) -> Unit) {
    val columns = component.props.propInt("columns", default = 2).coerceIn(1, 4)
    val items = component.children.ifEmpty {
        component.props.propString("items")
            .split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { item ->
                PluginPanelComponent(
                    key = item,
                    type = PluginPanelComponent.TYPE_TEXT,
                    props = kotlinx.serialization.json.buildJsonObject {
                        put("text", item)
                    },
                )
            }
    }
    if (items.isEmpty()) {
        SchemaUnknown(component)
        return
    }
    items.chunked(columns).forEach { rowItems ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowItems.forEach { item ->
                Column(modifier = Modifier.weight(1f)) {
                    SchemaComponent(item, enabled, onEvent)
                }
            }
            repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SchemaProgress(component: PluginPanelComponent) {
    val label = component.props.propString("label", "title")
    val value = component.props.propDouble("value", default = -1.0)
    val indeterminate = value < 0
    Column(modifier = Modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!indeterminate) {
                    Text(
                        text = "${value.toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (indeterminate) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { (value / 100.0).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SchemaChart(component: PluginPanelComponent) {
    // 图表轻量实现：条形图（props.values 逗号分隔数字，labels 对应标签）
    val values = component.props.propString("values")
        .split(',', ';')
        .mapNotNull { it.trim().toDoubleOrNull() }
    val labels = component.props.propString("labels")
        .split(',', ';')
        .map { it.trim() }
    if (values.isEmpty()) {
        SchemaUnknown(component)
        return
    }
    val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(0.0001)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val title = component.props.propString("title")
        if (title.isNotEmpty()) {
            Text(text = title, style = MaterialTheme.typography.titleSmallEmphasized)
        }
        values.forEachIndexed { index, v ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = labels.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "#${index + 1}"
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 72.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (v / max).toFloat().coerceIn(0.02f, 1f))
                            .height(14.dp)
                            .padding(vertical = 3.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.small,
                            ),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatSliderValue(v),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 未知组件安全降级：灰字占位提示，保证插件 schema 写错只影响该节点 */
@Composable
private fun SchemaUnknown(component: PluginPanelComponent) {
    Text(
        text = "未知组件 ${component.type.ifEmpty { "(无类型)" }}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
