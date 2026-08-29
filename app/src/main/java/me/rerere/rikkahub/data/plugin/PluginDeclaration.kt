package me.rerere.rikkahub.data.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * dsh 插件声明（plugin.json 格式），对齐 design.md §5。
 *
 * 与 RikkaHub 现有的 [PluginInfo] 样本格式兼容：保留 name/version/description
 * 并新增 dsh 特有字段（entry/capabilities/services）。
 */
@Serializable
data class PluginDeclaration(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    /** 插件入口：JS 文件相对路径（纯 JS 插件）或 Kotlin 类名（Kotlin 插件） */
    val entry: String? = null,
    /** 插件类型：kotlin / js / panel */
    val kind: PluginDeclarationKind = PluginDeclarationKind.JS,
    /** 声明依赖的其他插件 ID（inject 前须加载） */
    val dependencies: List<String> = emptyList(),
    /** 声明可访问的能力缝白名单（R7.4） */
    val capabilities: List<String> = emptyList(),
    /** 声明对外提供的服务名列表 */
    val services: List<String> = emptyList(),
    /** 插件提供的系统提示片段 */
    val systemPrompt: String = "",
    /** 动态 Hook 声明 */
    val hooks: List<PluginHookDeclaration> = emptyList(),
    /** 提供的工具声明（name/schema/description） */
    val tools: List<PluginToolDeclaration> = emptyList(),
    /** 扩展配置（面板页面等） */
    val config: JsonObject? = null,
)

enum class PluginDeclarationKind { KOTLIN, JS, PANEL }

@Serializable
data class PluginHookDeclaration(
    val name: String,
    val description: String = "",
    val timeoutMs: Long = 3000L,
)

@Serializable
data class PluginToolDeclaration(
    val name: String,
    val description: String = "",
    /** 作为工具 schema 的 JSON 对象 */
    val schema: JsonObject? = null,
)