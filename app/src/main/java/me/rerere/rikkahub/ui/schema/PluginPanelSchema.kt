package me.rerere.rikkahub.ui.schema

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 插件面板 schema（panel.json）数据模型（design.md D2.2 schema 轨）。
 *
 * 结构：
 * ```json
 * {
 *   "version": 1,
 *   "title": "面板标题",
 *   "components": [
 *     { "key": "greet", "type": "button", "props": { "label": "问好" } },
 *     { "key": "card1", "type": "card", "props": { "title": "统计" },
 *       "children": [ { "key": "t1", "type": "text", "props": { "text": "共 3 条" } } ] }
 *   ]
 * }
 * ```
 */
@Serializable
data class PluginPanelSchema(
    val version: Int = 1,
    val title: String = "",
    val components: List<PluginPanelComponent> = emptyList(),
)

/** schema 组件节点：type 决定渲染器，props 为宽松 JSON（按组件类型安全读取），children 递归 */
@Serializable
data class PluginPanelComponent(
    val key: String = "",
    val type: String = "",
    val props: JsonObject = JsonObject(emptyMap()),
    val children: List<PluginPanelComponent> = emptyList(),
) {
    companion object {
        // 类型化组件目录（design.md R4.2）；渲染器见 SchemaPanelRenderer
        const val TYPE_CARD = "card"
        const val TYPE_TEXT = "text"
        const val TYPE_BUTTON = "button"
        const val TYPE_TOGGLE = "toggle"
        const val TYPE_SLIDER = "slider"
        const val TYPE_SELECT = "select"
        const val TYPE_LIST = "list"
        const val TYPE_GRID = "grid"
        const val TYPE_SECTION = "section"
        const val TYPE_CHART = "chart"
        const val TYPE_PROGRESS = "progress"
        const val TYPE_MARKDOWN = "markdown"

        /** 渲染器认识的全部组件类型（未知类型安全降级占位） */
        val KNOWN_TYPES = setOf(
            TYPE_CARD, TYPE_TEXT, TYPE_BUTTON, TYPE_TOGGLE, TYPE_SLIDER, TYPE_SELECT,
            TYPE_LIST, TYPE_GRID, TYPE_SECTION, TYPE_CHART, TYPE_PROGRESS, TYPE_MARKDOWN,
        )
    }
}

/** schema 事件（按钮点击 / 开关切换 / 滑杆 / 选中项），回传插件脚本处理 */
data class SchemaPanelEvent(
    val componentKey: String,
    val action: String,
    val value: String? = null,
)

/** props 宽松读取工具：缺失/类型不符一律回退默认值，schema 来自插件包不可信任 */
internal fun JsonObject.propString(vararg keys: String, default: String = ""): String {
    for (key in keys) {
        val v = this[key] as? JsonPrimitive ?: continue
        val s = v.contentOrNullSafe()
        if (s.isNotEmpty()) return s
    }
    return default
}

internal fun JsonObject.propInt(vararg keys: String, default: Int): Int {
    for (key in keys) {
        val v = this[key] as? JsonPrimitive ?: continue
        v.intOrNull?.let { return it }
    }
    return default
}

internal fun JsonObject.propDouble(vararg keys: String, default: Double): Double {
    for (key in keys) {
        val v = this[key] as? JsonPrimitive ?: continue
        v.doubleOrNull?.let { return it }
    }
    return default
}

internal fun JsonObject.propBoolean(vararg keys: String, default: Boolean): Boolean {
    for (key in keys) {
        val v = this[key] as? JsonPrimitive ?: continue
        v.booleanOrNull?.let { return it }
    }
    return default
}

private fun JsonPrimitive.contentOrNullSafe(): String = contentOrNull ?: ""

/** panel.json 解析；非法结构返回 null（渲染器呈现加载失败占位而非崩溃） */
object PluginPanelSchemaParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): PluginPanelSchema? = runCatching {
        json.decodeFromString(PluginPanelSchema.serializer(), text)
    }.getOrNull()?.takeIf { it.components.isNotEmpty() }
}
