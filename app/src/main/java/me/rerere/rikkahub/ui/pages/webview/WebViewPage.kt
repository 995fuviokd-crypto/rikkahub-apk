package me.rerere.rikkahub.ui.pages.webview

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Refresh01
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.script.ScriptRuntime
import me.rerere.rikkahub.data.plugin.PluginBoundary
import me.rerere.rikkahub.data.plugin.PluginJsBridge
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.theme.JetbrainsMono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewPage(url: String, contentId: String, pluginId: String = "") {
    val context = LocalContext.current
    // R3.1 异步桥接线载体：执行作用域 + 主线程 handler + 延迟绑定的 WebView 状态
    val pageScope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val asyncStateHolder = remember {
        mutableStateOf<me.rerere.rikkahub.ui.components.webview.WebViewState?>(null)
    }
    // D1.1 懒加载：pluginId 为空（普通网页/Markdown 预览）时零插件依赖解析；
    // 非空时才解析插件链，且经 PluginBoundary 包裹——依赖构造失败呈现占位态而非崩溃
    val pluginDeps = if (pluginId.isNotEmpty()) {
        // remember 内非组合上下文，不能用 @Composable koinInject；经全局 Koin 实例
        // 解析（项目既有模式），任何失败（缺定义/构造异常）被 PluginBoundary 捕获为降级态
        remember(pluginId) {
            PluginBoundary.guard("webview plugin deps[$pluginId]") {
                val koin = org.koin.java.KoinJavaComponent.getKoin()
                Triple(
                    koin.get<PluginManager>(),
                    koin.get<ScriptRuntime>(),
                    koin.get<me.rerere.rikkahub.data.plugin.CordisPluginBridge>(),
                )
            }
        }
    } else {
        null
    }

    when (pluginDeps) {
        is PluginBoundary.Result.Err -> {
            // 插件运行环境不可用：页面本体保持可打开，正文区降级为占位说明
            PluginUnavailablePlaceholder(pluginId)
            return
        }

        null, is PluginBoundary.Result.Ok -> {}
    }

    // R3.1/R3.2 异步桥：实例独立 remember 以便 DisposableEffect 生命周期收口
    val cordisJsBridge = if (pluginId.isNotEmpty() && pluginDeps is PluginBoundary.Result.Ok) {
        val cordisBridge = pluginDeps.value.third
        remember(pluginId) {
            cordisBridge.createJsBridge(
                pluginId = pluginId,
                asyncScope = pageScope,
                resultDispatcher = { js ->
                    mainHandler.post {
                        asyncStateHolder.value?.webView?.evaluateJavascript(js, null)
                    }
                },
            )
        }
    } else {
        null
    }

    val interfaces = if (pluginId.isNotEmpty() && pluginDeps is PluginBoundary.Result.Ok) {
        val (pluginManager, scriptRuntime, _) = pluginDeps.value
        remember(pluginId, cordisJsBridge) {
            buildMap<String, Any> {
                put("AndroidPlugin", PluginJsBridge(pluginId, pluginManager, scriptRuntime))
                cordisJsBridge?.let { put("CordisBridge", it) }
            }
        }
    } else {
        emptyMap()
    }

    // R3.2 页面离开即解绑事件订阅（取消时监听器清理）
    androidx.compose.runtime.DisposableEffect(cordisJsBridge) {
        onDispose { cordisJsBridge?.release() }
    }
    val state = if (url.isNotEmpty()) {
        rememberWebViewState(
            url = url,
            interfaces = interfaces,
            settings = {
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            })
    } else {
        val content = remember(contentId) {
            WebViewContentCache.load(context.cacheDir, contentId).orEmpty()
        }
        rememberWebViewState(
            data = content,
            baseUrl = WEB_VIEW_BASE_URL,
            mimeType = "text/html",
            interfaces = interfaces,
            settings = {
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        )
    }
    // R3.1：异步结果回推经 holder 找到当前 WebView 实例（state 晚于 interfaces 创建）
    asyncStateHolder.value = state

    // R4.2 web 轨设计体系注入：插件面板页加载完成后注入 Material3 动态色 CSS 变量
    // 与 cordis.css 轻量组件样式（页面自带样式优先，变量仅提供取色基准）
    val colorScheme = MaterialTheme.colorScheme
    LaunchedEffect(state.isLoading, pluginId, colorScheme) {
        if (pluginId.isNotEmpty() && !state.isLoading) {
            state.webView?.evaluateJavascript(
                me.rerere.rikkahub.ui.components.webview.CordisDesignTokens.injectionJs(colorScheme),
                null,
            )
        }
    }

    var showDropdown by remember { mutableStateOf(false) }
    var showConsoleSheet by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    BackHandler(state.canGoBack) {
        state.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.pageTitle?.takeIf { it.isNotEmpty() } ?: state.currentUrl
                        ?: "",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleSmall
                    )
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = { state.reload() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = "Refresh")
                    }

                    IconButton(
                        onClick = { state.goForward() },
                        enabled = state.canGoForward
                    ) {
                        Icon(HugeIcons.ArrowRight01, contentDescription = "Forward")
                    }

                    val urlHandler = LocalUriHandler.current
                    IconButton(
                        onClick = { showDropdown = true }
                    ) {
                        Icon(HugeIcons.MoreVertical, contentDescription = "More options")

                        DropdownMenu(
                            expanded = showDropdown,
                            onDismissRequest = { showDropdown = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open in Browser") },
                                leadingIcon = { Icon(HugeIcons.Earth, contentDescription = null) },
                                onClick = {
                                    showDropdown = false
                                    state.currentUrl?.let { url ->
                                        if (url.isNotBlank()) {
                                            urlHandler.openUri(url)
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Console Logs") },
                                leadingIcon = { Icon(HugeIcons.Bug01, contentDescription = null) },
                                onClick = {
                                    showDropdown = false
                                    showConsoleSheet = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) {
        WebView(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
        )
    }

    if (showConsoleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showConsoleSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Console Logs",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                SelectionContainer {
                    LazyColumn {
                        items(state.consoleMessages) { message ->
                            Text(
                                text = "${message.messageLevel().name}: ${message.message()}\n" +
                                    "Source: ${message.sourceId()}:${message.lineNumber()}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetbrainsMono,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                color = when (message.messageLevel().name) {
                                    "ERROR" -> MaterialTheme.colorScheme.error
                                    "WARNING" -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                if (state.consoleMessages.isEmpty()) {
                    Text(
                        text = "No console messages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

/** 插件运行环境不可用时的降级占位页（R1.3）：保留导航壳，正文区呈现结构化说明。 */
@Composable
private fun PluginUnavailablePlaceholder(pluginId: String) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.webview_page_plugin_title, pluginId),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleSmall
                    )
                },
                navigationIcon = {
                    BackButton()
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Bug01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(
                text = stringResource(R.string.webview_page_plugin_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.webview_page_plugin_unavailable_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
