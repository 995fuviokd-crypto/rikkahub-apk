package me.rerere.rikkahub.data.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Schema allowlist 工具：从工具 schema 中剥离 runtime 字段，
 * 只保留模型可见的声明字段（对齐 design.md §4 需求）。
 *
 * 这些字段仅供宿主内部使用（如内部标记），不应进入 request/header 的模型可见 schema：
 * - 以 `$` 开头的字段（内部保留）
 * - 标记为 `runtime` 的属性
 * - `hidden` 属性
 */
internal object ToolSchemaAllowlist {

    /** 默认剥离前缀/标记。 */
    private val stripPrefixes = listOf("$", "runtime", "internal")

    private val stripKeys = setOf("hidden", "runtime", "internal")

    /** 是否允许该字段进入模型可见 schema。 */
    fun isAllowed(key: String): Boolean {
        if (key in stripKeys) return false
        return stripPrefixes.none { key.startsWith(it) }
    }

    /** 递归剥离 schema 中的 runtime 字段。 */
    fun strip(schema: JsonElement?): JsonElement? {
        if (schema == null) return null
        return stripElement(schema)
    }

    private fun stripElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            buildJsonObject {
                element.forEach { (key, value) ->
                    if (isAllowed(key)) {
                        put(key, stripElement(value))
                    }
                }
            }
        }

        is JsonArray -> buildJsonArray {
            element.forEach { add(stripElement(it)) }
        }

        is JsonPrimitive -> element
    }
}