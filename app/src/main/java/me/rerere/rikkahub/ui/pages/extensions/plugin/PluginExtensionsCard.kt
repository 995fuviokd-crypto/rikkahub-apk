package me.rerere.rikkahub.ui.pages.extensions.plugin

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.data.plugin.PluginExtensionAction
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.utils.openUrl

/** 解析插件 webview 资源引用 `plugin://<插件id>/<web路径>` */
fun parsePluginWebPayload(payload: String): Pair<String?, String?> {
    if (payload.startsWith("plugin://")) {
        val rest = payload.removePrefix("plugin://")
        val slash = rest.indexOf('/')
        if (slash <= 0) return rest to "index.html"
        return rest.substring(0, slash) to rest.substring(slash + 1)
    }
    return null to null
}

/**
 * 处理插件扩展动作点击（非 Composable，可在任意 onClick 中调用）。
 * - url：打开外部链接
 * - copy：复制到剪贴板
 * - webview：打开插件页面（http(s) 远程链接，或 plugin://<id>/<path> 读取插件包内 web/ 资源）
 * - 其他：视为提示词，通过 onPromptAction 回调交给调用方展示预览对话框
 */
fun performExtensionAction(
    action: PluginExtensionAction,
    pluginManager: PluginManager,
    context: Context,
    onOpenWebView: (url: String, contentId: String) -> Unit,
    onPromptAction: (PluginExtensionAction) -> Unit,
) {
    fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("plugin", text))
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
    when (action.target) {
        "url" -> context.openUrl(action.payload)
        "copy" -> copy(action.payload)
        "webview" -> {
            if (action.payload.startsWith("http://") || action.payload.startsWith("https://")) {
                onOpenWebView(action.payload, "")
            } else {
                val (pluginId, path) = parsePluginWebPayload(action.payload)
                if (pluginId != null) {
                    val html = pluginManager.loadWebResource(pluginId, path ?: "index.html")
                    if (html != null) {
                        val contentId = WebViewContentCache.store(context.cacheDir, html)
                        onOpenWebView("", contentId)
                    } else {
                        Toast.makeText(context, "插件页面不存在", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "无法打开插件页面", Toast.LENGTH_SHORT).show()
                }
            }
        }
        else -> onPromptAction(action)
    }
}

/** 展示插件提示词动作的预览对话框（可复制内容） */
@Composable
fun PluginActionPromptDialog(
    action: PluginExtensionAction,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
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
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("plugin", action.payload))
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }) { Text("复制内容") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 渲染已启用插件在指定 scope 的扩展能力入口。
 * scope: "settings"（设置页扩展）/ "home"（主界面入口）/ "sidebar"（侧边栏入口）
 */
@Composable
fun PluginExtensionsCard(
    enabledPlugins: Set<String>,
    pluginManager: PluginManager,
    scope: String,
    onOpenWebView: (url: String, contentId: String) -> Unit = { _, _ -> },
) {
    val actions = pluginManager.enabledExtensionActions(enabledPlugins, scope)
    if (actions.isEmpty()) return
    val context = LocalContext.current
    var promptAction by remember { mutableStateOf<PluginExtensionAction?>(null) }

    CardGroup(
        title = { Text(when (scope) {
            "settings" -> "插件扩展"
            "sidebar" -> "插件入口"
            else -> "插件入口"
        }) },
    ) {
        actions.forEach { action ->
            item(
                onClick = {
                    performExtensionAction(action, pluginManager, context, onOpenWebView) {
                        promptAction = it
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
        PluginActionPromptDialog(action = action, onDismiss = { promptAction = null })
    }
}
