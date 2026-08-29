package me.rerere.rikkahub.data.tools

import me.rerere.rikkahub.data.cordis.CordisEvent
import me.rerere.rikkahub.data.cordis.CordisEventBus
import me.rerere.rikkahub.data.cordis.DispatchMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具注册表：维护 [ToolDefinition] 集合，工具集合变化时派发 `tools/change`。
 */
internal class ToolRegistry(private val eventBus: CordisEventBus) {
    private val definitions = linkedMapOf<String, ToolDefinition>()

    /** 注册工具；同名重复注册返回 false。 */
    fun register(tool: ToolDefinition): Boolean {
        if (definitions.containsKey(tool.name)) return false
        definitions[tool.name] = tool
        notifyChanged()
        return true
    }

    fun unregister(name: String): Boolean {
        val removed = definitions.remove(name)
        if (removed != null) notifyChanged()
        return removed != null
    }

    fun get(name: String): ToolDefinition? = definitions[name]

    fun all(): List<ToolDefinition> = definitions.values.toList()

    /** 工具集合变化 → `tools/change`（Emit 语义，供插件感知）。 */
    private fun notifyChanged() {
        val names = definitions.keys.joinToString(",")
        kotlinx.coroutines.runBlocking {
            eventBus.emit(
                CordisEvent(
                    name = "tools/change",
                    payload = buildJsonObject {
                        put("tools", names)
                        put("count", definitions.size)
                    }
                )
            )
        }
    }

    /** 模型可见 schema：all() 的 schema 字段集合。 */
    fun schemas(): List<JsonObject> =
        all().mapNotNull { tool ->
            tool.schema?.let { schema ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", schema)
                }
            }
        }
}