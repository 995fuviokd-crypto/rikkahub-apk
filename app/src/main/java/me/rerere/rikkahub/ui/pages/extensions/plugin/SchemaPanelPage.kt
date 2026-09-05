package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.plugin.PluginPanelSpec
import me.rerere.rikkahub.data.script.ScriptRuntime
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.schema.PluginPanelSchema
import me.rerere.rikkahub.ui.schema.PluginPanelSchemaParser
import me.rerere.rikkahub.ui.schema.SchemaPanelEvent
import me.rerere.rikkahub.ui.schema.SchemaPanelRenderer
import org.koin.compose.koinInject

/**
 * Schema 轨插件面板宿主页（design.md D2.2）。
 *
 * - 加载 plugin.json panel.entry（缺省 panel.json）描述的组件树并原生渲染
 * - 交互事件回传：后台调用插件脚本 onAction 工具（panel.script 声明的处理脚本，
 *   无声明时回退 script/ 目录入口），脚本返回 {"schema": {...}} 即增量重渲染
 * - 纯静态面板（无脚本）事件仅本地消费，不影响展示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaPanelPage(pluginId: String) {
    val pluginManager: PluginManager = koinInject()
    val scriptRuntime: ScriptRuntime = koinInject()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var schema by remember(pluginId) { mutableStateOf<PluginPanelSchema?>(null) }
    var loadError by remember(pluginId) { mutableStateOf<String?>(null) }
    var pendingEvent by remember(pluginId) { mutableStateOf(false) }
    var interactive by remember(pluginId) { mutableStateOf(false) }

    // 首次加载：解析面板规格 + schema 文件
    LaunchedEffect(pluginId) {
        withContext(Dispatchers.IO) {
            val installed = pluginManager.listPlugins().firstOrNull { it.id == pluginId }
            val spec = pluginManager.resolvePanelSpec(pluginId, installed?.info)
            if (spec == null || spec.type != PluginPanelSpec.TYPE_SCHEMA) {
                loadError = "未找到可用的 schema 面板（panel.json 缺失或声明无效）"
                return@withContext
            }
            val entryFile = File(pluginManager.getPluginDir(pluginId), spec.entry)
            val text = runCatching { entryFile.readText() }.getOrNull()
            if (text == null) {
                loadError = "面板文件读取失败：${spec.entry}"
                return@withContext
            }
            val parsed = PluginPanelSchemaParser.parse(text)
            if (parsed == null) {
                loadError = "面板 schema 解析失败（components 为空或格式非法）"
                return@withContext
            }
            schema = parsed
            // 有事件处理脚本（显式声明或 script/ 目录存在）时开启交互回传
            val dir = pluginManager.getPluginDir(pluginId)
            val declaredScript = spec.script.isNotBlank() && File(dir, spec.script).isFile
            val scriptDir = ScriptRuntime.scriptDir(dir)
            val hasScriptDir = scriptDir.isDirectory &&
                scriptDir.listFiles().orEmpty().any { it.extension == "js" }
            interactive = declaredScript || hasScriptDir
        }
    }

    // 事件回传：调用插件脚本 onAction 工具；返回 {"schema": {...}} 时整体增量重渲染
    val onEvent: (SchemaPanelEvent) -> Unit = { event ->
        if (interactive && !pendingEvent) {
            pendingEvent = true
            scope.launch {
            val result = withContext(Dispatchers.IO) {
                val args = buildJsonObject {
                    put("component", event.componentKey)
                    put("action", event.action)
                    event.value?.let { put("value", it) }
                }.toString()
                runCatching {
                    scriptRuntime.runTool(
                        pluginManager.getPluginDir(pluginId),
                        pluginId,
                        "onAction",
                        args,
                    )
                }.getOrNull()
            }
            pendingEvent = false
            val newSchema = result
                ?.takeIf { it.ok }
                ?.data
                ?.let { it as? JsonObject }
                ?.get("schema")
                ?.let { it as? JsonObject }
                ?.let { json ->
                    runCatching {
                        Json.decodeFromJsonElement(PluginPanelSchema.serializer(), json)
                    }.getOrNull()
                }
            if (newSchema != null) {
                schema = newSchema
            }
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(schema?.title?.ifBlank { "插件面板" } ?: "插件面板") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val currentSchema = schema
        when {
            loadError != null -> SchemaPanelMessage(text = loadError ?: "")

            currentSchema == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("加载面板中…", style = MaterialTheme.typography.bodySmall)
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = currentSchema.components.size,
                    key = { index -> currentSchema.components[index].key.ifBlank { "component-$index" } },
                ) { index ->
                    val component = currentSchema.components[index]
                    key(component.key.ifBlank { "component-$index" }) {
                        SchemaPanelRenderer(
                            components = listOf(component),
                            enabled = !pendingEvent,
                            onEvent = onEvent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SchemaPanelMessage(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
