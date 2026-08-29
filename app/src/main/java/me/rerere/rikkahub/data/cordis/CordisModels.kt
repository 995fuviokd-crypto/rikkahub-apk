package me.rerere.rikkahub.data.cordis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 注册的模型声明：ctx.model(name, config) */
@Serializable
data class CordisModel(
    val name: String,
    val config: JsonObject,
    val pluginId: String,
)

/** 插件的模型配置速写：兼容 DSH defineModel 的常见字段 */
@Serializable
data class CordisModelConfig(
    val model: String = "",
    val name: String = "",
    val description: String = "",
    val temperature: Double? = null,
    val platform: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val maxTokens: Int? = null,
)