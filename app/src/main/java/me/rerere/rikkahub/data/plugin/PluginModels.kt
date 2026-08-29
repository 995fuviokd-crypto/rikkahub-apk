package me.rerere.rikkahub.data.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    /**
     * 动态 Hook 声明。脚本须导出 rikkaHook(ctx) 函数，
     * 宿主在对应时机链式调用并采用返回值作为新的上下文。
     * 支持的 name 见 [PluginHook.KNOWN] 白名单
     */
    val hooks: List<PluginHook> = emptyList(),
    /** 资源类型：plugin/skill/mcp/json/other */
    val type: String = "plugin",
    /** 自定义标签（用于市场分类与搜索） */
    val tags: List<String> = emptyList(),
    /** 扩展能力：主界面/设置页等处的新增功能与图标入口 */
    val extensionPoints: PluginExtensionPoints = PluginExtensionPoints(),
    /**
     * 插件提供的工作区 CLI 命令所依赖的 npm 包（如 DSH 插件的 bin 包）。
     * 非空表示该插件需要工作区 Node.js 运行环境；用于插件列表的环境提醒与一键补全，
     * 也支持"安装到工作区"预装（全局安装后 npx 直接命中本地, 无需每次联网解析）。
     */
    val npmPackages: List<String> = emptyList(),
    /**
     * 插件配置声明（对标 DSH 用户层配置热更新）。
     * plugin.json 中 key 为 "config"（见 [PluginConfigSchema]）。
     * 声明后市场页/技能页已安装详情渲染配置表单，编辑结果保存到
     * settings.pluginConfigs；由于宿主每轮生成实时读取 settings，
     * 配置变更立即作用于 systemPrompt 注入与 Hook 链，无需重建会话。
     */
    @SerialName("config")
    val configSchema: PluginConfigSchema? = null,
)

/**
 * 插件配置声明（plugin.json 的 "config" 字段）。
 * 对标 DSH 插件：bundle 声明配置 schema，用户可按需覆盖，宿主读取时实时合并默认值。
 */
@Serializable
data class PluginConfigSchema(
    /** 配置字段（按声明顺序渲染表单） */
    val fields: List<PluginConfigField> = emptyList(),
)

/** 单个配置字段定义 */
@Serializable
data class PluginConfigField(
    /** 配置项 key（JSON 中的键名） */
    val key: String,
    /** 展示名，缺省用 key */
    val label: String = "",
    /** 控件类型：[PluginConfigField.TYPE_*]，缺省 text */
    val type: String = TYPE_TEXT,
    /** 字段说明（渲染为辅助文本） */
    val description: String = "",
    /** 是否必填（保存时为空值则提示） */
    val required: Boolean = false,
    /** 默认值（JSON 元素）：text/textarea/number/select 为字符串，bool 为 true/false，multi 为 JSON 数组 */
    val default: JsonElement? = null,
    /** select / multi 的候选选项 */
    val options: List<String> = emptyList(),
    /** secret 等输入框的占位/提示文本 */
    val placeholder: String = "",
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_TEXTAREA = "textarea"
        const val TYPE_NUMBER = "number"
        const val TYPE_BOOL = "bool"
        const val TYPE_SELECT = "select"
        const val TYPE_MULTI = "multi"
        const val TYPE_SECRET = "secret"

        /** 需要用户输入文本的控件类型（其余为选择类） */
        val TEXTUAL = setOf(TYPE_TEXT, TYPE_TEXTAREA, TYPE_NUMBER, TYPE_SECRET)
    }
}

@Serializable
data class PluginAction(
    val label: String,
    val prompt: String,
)

/**
 * 插件动态 Hook 声明。脚本入口（manifest main 或首个 js 文件）须导出：
 *
 *   function rikkaHook(ctx) { ctx.text = ctx.text.trim(); return ctx; }
 *   module.exports = { rikkaHook };
 *
 * 宿主在对应时机以 JSON 上下文调用，采用返回值（JSON 对象）作为修改后的上下文；
 * 返回非对象或抛异常时保持原上下文继续，单个插件失败不影响 Hook 链。
 */
@Serializable
data class PluginHook(
    /** 钩子名称：见 [PluginHook.KNOWN] 白名单（message/request/title 系列） */
    val name: String,
    val description: String = "",
    /** 单次执行超时（毫秒），超时视为失败并跳过 */
    val timeoutMs: Long = 3000L,
) {
    companion object {
        const val MESSAGE_BEFORE_SEND = "message:beforeSend"
        const val MESSAGE_AFTER_GENERATE = "message:afterGenerate"
        const val REQUEST_BEFORE_SEND = "request:beforeSend"
        const val TITLE_AFTER_GENERATE = "title:afterGenerate"
        const val MESSAGE_BEFORE_RENDER = "message:beforeRender"

        /** 宿主支持的钩子白名单 */
        val KNOWN = setOf(
            MESSAGE_BEFORE_SEND,
            MESSAGE_AFTER_GENERATE,
            REQUEST_BEFORE_SEND,
            TITLE_AFTER_GENERATE,
            MESSAGE_BEFORE_RENDER,
        )
    }
}

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
    /** 聊天页顶部栏入口（下拉菜单形式） */
    val chatToolbarActions: List<PluginExtensionAction> = emptyList(),
    /** 聊天输入栏入口（提示词填入输入框，webview 打开插件页面） */
    val inputBarActions: List<PluginExtensionAction> = emptyList(),
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
    const val TYPE_CHARACTER = "character"

    /** 资源类型预设（上传时可选） */
    val types = listOf(
        TYPE_PLUGIN,
        TYPE_SKILL,
        TYPE_MCP,
        TYPE_JSON,
        TYPE_CHARACTER,
        "other",
    )

    /** 类型标签的默认展示名 */
    fun typeLabel(type: String): String = when (type) {
        TYPE_PLUGIN -> "插件"
        TYPE_SKILL -> "技能"
        TYPE_MCP -> "MCP"
        TYPE_JSON -> "JSON 配置"
        TYPE_CHARACTER -> "角色卡"
        else -> type.ifBlank { "其他" }
    }

    /** 市场筛选维度：全部 + 类型 */
    val marketTypes = listOf(
        ALL,
        TYPE_PLUGIN,
        TYPE_SKILL,
        TYPE_MCP,
        TYPE_CHARACTER,
        TYPE_JSON,
    )
}
