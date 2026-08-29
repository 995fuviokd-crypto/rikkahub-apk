package me.rerere.rikkahub.data.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 插件配置存取。每插件一份配置对象 JSON，保存在 settings.pluginConfigs。
 *
 * 热更新语义：编辑结果写入 settings 后 settingsFlow 立即发射（内置 SupportStateFlow），
 * ChatService 每轮生成从 settings 快照读取配置、Hook 链 dispatchHook 时实时读取，
 * 因此配置变更即时作用于 systemPrompt 注入与插件 Hook 链，无需重建会话或重启应用。
 */
class PluginConfigRepository(
    private val settingsStore: SettingsStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** 插件的原始配置 JSON 文本（用户已保存值），无保存过为空 */
    fun configJsonFor(pluginId: String): String? =
        settingsStore.settingsFlow.value.pluginConfigs[pluginId]?.takeIf { it.isNotBlank() }

    /** 插件的原始配置对象（用户已保存值） */
    fun configFor(pluginId: String): JsonObject? =
        configJsonFor(pluginId)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    /** 全部插件配置（插件 id -> 配置 JSON 文本） */
    fun allConfigs(): Map<String, String> = settingsStore.settingsFlow.value.pluginConfigs

    /**
     * 合并默认值后的完整配置 JSON 文本。
     * 声明了 config schema 的插件：以用户已保存值优先，缺失字段用 schema 默认值补全；
     * 未声明 schema 的返回 null（不注入）。
     */
    fun resolvedConfigJson(info: PluginInfo?): String? {
        val schema = info?.configSchema ?: return null
        if (schema.fields.isEmpty()) return null
        val existing = configFor(info.id) ?: JsonObject(emptyMap())
        val merged = buildJsonObject {
            schema.fields.forEach { field ->
                val value = existing[field.key] ?: field.default
                if (value != null) put(field.key, value)
            }
        }
        return merged.toString()
    }

    /** 保存（合并后的）配置对象；写入后 settingsFlow 立即发射，后续生成/Hook 实时生效 */
    suspend fun saveConfig(pluginId: String, merged: JsonObject) {
        settingsStore.update { it.copy(pluginConfigs = it.pluginConfigs + (pluginId to merged.toString())) }
    }

    /** 清空某插件配置（回归 schema 默认值） */
    suspend fun clearConfig(pluginId: String) {
        settingsStore.update { it.copy(pluginConfigs = it.pluginConfigs - pluginId) }
    }
}