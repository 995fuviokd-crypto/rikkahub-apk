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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.androidvm.BlackBoxHost
import me.rerere.androidvm.R
import me.rerere.androidvm.VmCatalog
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmImage
import me.rerere.androidvm.VmVM
import me.rerere.androidvm.engine.GuestRomNative

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
    // 引擎接入状态(Bcore 反射可见性 / guestrom 原生库): 未接入的条目禁选并标注, 避免创建即失败
    val blackBoxReady = remember { BlackBoxHost.isAvailable() }
    val guestRomReady = remember { GuestRomNative.available }
    val unavailableHint = stringResource(R.string.vm_engine_not_ready)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = {
                    selected?.let { vm.createFromImage(it, name) }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.vm_create_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vm_create_cancel)) } },
        title = { Text(stringResource(R.string.vm_create_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.vm_create_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    items(VmCatalog.images, key = { it.id }) { image ->
                        val engineReady = when (image.engineType) {
                            VmEngineType.ANDROID -> blackBoxReady
                            VmEngineType.GUEST_ROM -> guestRomReady
                            VmEngineType.LINUX -> true
                        }
                        LaunchedEffect(engineReady) {
                            if (!engineReady && selected?.id == image.id) selected = null
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected == image,
                                    enabled = engineReady,
                                    onClick = { selected = image },
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == image,
                                enabled = engineReady,
                                onClick = { selected = image },
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                val tag = when (image.engineType) {
                                    VmEngineType.ANDROID -> stringResource(R.string.vm_engine_android)
                                    VmEngineType.GUEST_ROM -> stringResource(R.string.vm_engine_guest)
                                    VmEngineType.LINUX -> stringResource(R.string.vm_engine_linux)
                                }
                                val suffix = if (engineReady) "" else "  ·  $unavailableHint"
                                Text(
                                    "${image.systemLabel}  ·  $tag$suffix",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (engineReady) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.outline,
                                )
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
