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
)

@Serializable
data class PluginAction(
    val label: String,
    val prompt: String,
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
    val known = listOf(
        ALL,
        "development",
        "productivity",
        "creative",
        "knowledge",
        "automation",
        "media",
        "other",
    )
}
