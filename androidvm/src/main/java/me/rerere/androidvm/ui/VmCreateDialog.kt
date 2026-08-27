package me.rerere.androidvm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.androidvm.VmCatalog
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmImage
import me.rerere.androidvm.VmVM

/**
 * 创建虚拟机实例：选择系统镜像 + 命名。仿光速的「新建实例 / ROM 选择」。
 */
@Composable
fun VmCreateDialog(
    vm: VmVM,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<VmImage?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = {
                    selected?.let { vm.createFromImage(it, name) }
                    onDismiss()
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("新建虚拟机") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    items(VmCatalog.images, key = { it.id }) { image ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected == image,
                                    onClick = { selected = image },
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == image,
                                onClick = { selected = image },
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                val tag = if (image.engineType == VmEngineType.ANDROID) "Android" else "Linux"
                                Text("${image.systemLabel}  ·  $tag", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${image.description}  ·  约 ${image.sizeHintMb}MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
