package me.rerere.rikkahub.ui.pages.extensions.plugin

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.data.plugin.PluginExtensionAction
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.utils.openUrl

/**
 * 渲染已启用插件在指定 scope 的扩展能力入口。
 * scope: "settings"（设置页扩展）/ "home"（主界面入口）
 */
@Composable
fun PluginExtensionsCard(
    enabledPlugins: Set<String>,
    pluginManager: PluginManager,
    scope: String,
) {
    val actions = pluginManager.enabledExtensionActions(enabledPlugins, scope)
    if (actions.isEmpty()) return
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var promptAction by remember { mutableStateOf<PluginExtensionAction?>(null) }

    CardGroup(
        title = { Text(if (scope == "settings") "插件扩展" else "插件入口") },
    ) {
        actions.forEach { action ->
            item(
                onClick = {
                    when (action.target) {
                        "url" -> context.openUrl(action.payload)
                        "copy" -> {
                            clipboardManager.setText(AnnotatedString(action.payload))
                            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                        else -> promptAction = action
                    }
                },
                leadingContent = { Icon(HugeIcons.Puzzle, null, tint = MaterialTheme.colorScheme.primary) },
                supportingContent = if (action.description.isNotBlank()) {
                    { Text(action.description) }
                } else {
                    null
                },
                headlineContent = { Text(action.label) },
            )
        }
    }

    promptAction?.let { action ->
        AlertDialog(
            onDismissRequest = { promptAction = null },
            title = { Text(action.label) },
            text = {
                Column {
                    Text(
                        action.payload,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(action.payload))
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }) { Text("复制内容") }
                }
            },
            confirmButton = {
                TextButton(onClick = { promptAction = null }) { Text("关闭") }
            },
        )
    }
}
