package me.rerere.rikkahub.data.plugin

import kotlinx.serialization.Serializable

/** 插件元数据（plugin.json），位于插件包根目录 */
@Serializable
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val category: String = "general",
    val repository: String = "",
    /** 启用插件后注入助手系统提示的文本 */
    val systemPrompt: String = "",
    /** 聊天输入栏快捷操作 */
    val actions: List<PluginAction> = emptyList(),
    /** 资源类型：plugin/skill/mcp/json/other */
    val type: String = "plugin",
    /** 自定义标签（用于市场分类与搜索） */
    val tags: List<String> = emptyList(),
    /** 扩展能力：主界面/设置页等处的新增功能与图标入口 */
    val extensionPoints: PluginExtensionPoints = PluginExtensionPoints(),
)

@Serializable
data class PluginAction(
    val label: String,
    val prompt: String,
)

/**
 * 插件扩展能力声明。消费端按 scope 渲染动态入口，无需修改宿主代码。
 * target 取值：prompt（填入输入框/对话框提示词）、url（打开链接）、copy（复制到剪贴板）、
 * webview（打开插件页面，payload 为 http(s):// 链接或 plugin://<插件id>/<web路径>）。
 */
@Serializable
data class PluginExtensionPoints(
    /** 设置页扩展区块 */
    val settingsActions: List<PluginExtensionAction> = emptyList(),
    /** 主界面入口 */
    val homeActions: List<PluginExtensionAction> = emptyList(),
    /** 侧边栏入口 */
    val sidebarActions: List<PluginExtensionAction> = emptyList(),
)

@Serializable
data class PluginExtensionAction(
    val id: String,
    val label: String,
    val description: String = "",
    val target: String = "prompt",
    val payload: String = "",
)

/** 插件市场条目（索引仓库 plugins.json） */
@Serializable
data class PluginMarketEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val category: String = "general",
    val repository: String = "",
    val downloadUrl: String = "",
    /** 资源类型：plugin/skill/mcp/json/other */
    val type: String = "plugin",
    /** 自定义标签（用于市场分类与搜索） */
    val tags: List<String> = emptyList(),
)

/** 插件目录中的插件文件 */
data class PluginArchive(
    val info: PluginInfo,
    val fileName: String,
    val content: ByteArray,
) {
    val zipFileName: String get() = "${info.id}-${info.version}.zip"
}

object PluginCategories {
    const val ALL = "全部"
    const val TYPE_PLUGIN = "plugin"
    const val TYPE_SKILL = "skill"
    const val TYPE_MCP = "mcp"
    const val TYPE_JSON = "json"

    /** 资源类型预设（上传时可选） */
    val types = listOf(
        TYPE_PLUGIN,
        TYPE_SKILL,
        TYPE_MCP,
        TYPE_JSON,
        "other",
    )

    /** 类型标签的默认展示名 */
    fun typeLabel(type: String): String = when (type) {
        TYPE_PLUGIN -> "插件"
        TYPE_SKILL -> "技能"
        TYPE_MCP -> "MCP"
        TYPE_JSON -> "JSON 配置"
        else -> type.ifBlank { "其他" }
    }

    /** 市场筛选维度：全部 + 类型 + 常见分类 */
    val known = listOf(
        ALL,
        TYPE_PLUGIN,
        TYPE_SKILL,
        TYPE_MCP,
        TYPE_JSON,
        "development",
        "productivity",
        "creative",
        "knowledge",
        "automation",
        "media",
        "other",
    )

    /** 市场页分类筛选（按类型精简） */
    val marketTypes = listOf(
        ALL,
        TYPE_PLUGIN,
        TYPE_SKILL,
        TYPE_MCP,
    )
}
