package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginConfigField
import me.rerere.rikkahub.data.plugin.PluginConfigSchema

/**
 * 插件配置编辑对话框（对标 DSH 用户层配置热更新）。
 * 按 plugin.json "config" 声明的 schema 渲染表单，保存后立即写入 settings.pluginConfigs，
 * 宿主每轮生成实时读取 → 无需重启会话即生效。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PluginConfigDialog(
    plugin: InstalledPlugin,
    currentConfig: String?,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val info = plugin.info
    val schema: PluginConfigSchema = info?.configSchema ?: return
    if (schema.fields.isEmpty()) return

    val existing = remember(currentConfig) {
        runCatching { Json.parseToJsonElement(currentConfig ?: "{}").jsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
    }

    // 字段值：初始化取已保存值，缺失用 schema 默认值
    val values = remember(schema, currentConfig) {
        mutableStateMapOf<String, JsonElement>().apply {
            schema.fields.forEach { field ->
                put(field.key, existing[field.key] ?: defaultFor(field))
            }
        }
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    var visibleSecrets by remember(schema.fields) { mutableStateOf<Set<String>>(emptySet()) }

    fun save() {
        val missing = schema.fields.firstOrNull { field -> !satisfied(field, values[field.key]) }
        if (missing != null) {
            errorText = "「${missing.label.ifBlank { missing.key }}」为必填项"
            return
        }
        errorText = null
        val merged = buildJsonObject {
            values.forEach { (k, v) -> put(k, v) }
        }
        onSave(merged.toString())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 · ${info.name.ifBlank { plugin.id }}") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                schema.fields.forEach { field ->
                    val key = field.key
                    val label = field.label.ifBlank { key }
                    val isSecret = field.type == PluginConfigField.TYPE_SECRET
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (field.required) "$label *" else label,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            if (isSecret) {
                                Text(
                                    text = "（加密输入，仅本地保存）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (field.description.isNotBlank()) {
                            Text(
                                text = field.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (field.type) {
                            PluginConfigField.TYPE_SECRET -> {
                                val visible = key in visibleSecrets
                                OutlinedTextField(
                                    value = (values[key] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                                    onValueChange = { values[key] = JsonPrimitive(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    trailingIcon = {
                                        TextButton(onClick = {
                                            visibleSecrets = if (visible) visibleSecrets - key else visibleSecrets + key
                                        }) { Text(if (visible) "隐藏" else "显示") }
                                    },
                                    placeholder = { field.placeholder.takeIf { it.isNotBlank() }?.let { Text(it) } },
                                )
                            }

                            PluginConfigField.TYPE_TEXT,
                            PluginConfigField.TYPE_NUMBER,
                            -> OutlinedTextField(
                                value = (values[key] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                                onValueChange = { values[key] = JsonPrimitive(it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { field.placeholder.takeIf { it.isNotBlank() }?.let { Text(it) } },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (field.type == PluginConfigField.TYPE_NUMBER) {
                                        KeyboardType.Number
                                    } else {
                                        KeyboardType.Text
                                    },
                                ),
                            )

                            PluginConfigField.TYPE_TEXTAREA -> OutlinedTextField(
                                value = (values[key] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                                onValueChange = { values[key] = JsonPrimitive(it) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 6,
                            )

                            PluginConfigField.TYPE_BOOL -> {
                                val boolValue = (values[key] as? JsonPrimitive)?.booleanOrNull ?: false
                                Switch(
                                    checked = boolValue,
                                    onCheckedChange = { values[key] = JsonPrimitive(it) },
                                )
                            }

                            PluginConfigField.TYPE_SELECT -> {
                                val options = field.options
                                val selected = (values[key] as? JsonPrimitive)?.contentOrNull
                                    ?.takeIf { it in options } ?: options.firstOrNull().orEmpty()
                                SingleSelectMenu(
                                    label = selected,
                                    options = options,
                                    onSelect = { values[key] = JsonPrimitive(it) },
                                )
                            }

                            PluginConfigField.TYPE_MULTI -> {
                                val chosen = (values[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                                    ?.toSet().orEmpty()
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    field.options.forEach { option ->
                                        val checked = option in chosen
                                        FilterChip(
                                            selected = checked,
                                            onClick = {
                                                val next = if (checked) chosen - option else chosen + option
                                                values[key] = JsonArray(next.sorted().map { JsonPrimitive(it) })
                                            },
                                            label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                                        )
                                    }
                                }
                            }

                            else -> OutlinedTextField(
                                value = (values[key] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                                onValueChange = { values[key] = JsonPrimitive(it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                }
                errorText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }) { Text("保存并生效") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("恢复默认") }
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleSelectMenu(
    label: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 非空校验：required 字段必须给有效值 */
private fun satisfied(field: PluginConfigField, value: JsonElement?): Boolean {
    if (!field.required) return true
    return when (field.type) {
        PluginConfigField.TYPE_BOOL -> true
        PluginConfigField.TYPE_MULTI -> (value as? JsonArray)?.isNotEmpty() == true
        else -> (value as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
    }
}

/** 控件默认值：schema 声明优先，否则按类型取空态 */
private fun defaultFor(field: PluginConfigField): JsonElement = field.default ?: when (field.type) {
    PluginConfigField.TYPE_BOOL -> JsonPrimitive(false)
    PluginConfigField.TYPE_MULTI -> JsonArray(emptyList())
    PluginConfigField.TYPE_SELECT -> JsonPrimitive(field.options.firstOrNull().orEmpty())
    else -> JsonPrimitive("")
}