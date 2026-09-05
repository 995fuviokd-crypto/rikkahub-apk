package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialSettingsImportTest {

    // 官方 v24 Settings 没有这些字段（修改版新增）
    private val officialMissingFields = listOf(
        "ocrEnabled",
        "memoryRecallLimit",
        "memoryRetrievalMode",
        "memoryMinScore",
        "memoryJournalEnabled",
        "memoryModelId",
        "selfHostedModelId",
        "autoCompressEnabled",
        "autoCompressContextPercent",
        "autoCompressMaxMode",
        "autoReconnectEnabled",
        "autoReconnectMaxRetries",
        "multiRouteConcurrent",
        "displayScaleMode",
        "displayScaleDensityDpi",
        "keepAliveEnabled",
    )

    @Test
    fun `official v24 settings json without new fields should deserialize`() {
        val settings = Settings()
        val full = JsonInstant.encodeToString(settings)
        val jsonObj = JsonInstant.parseToJsonElement(full).jsonObject
        val official = jsonObj.toMutableMap().apply {
            officialMissingFields.forEach { remove(it) }
        }
        val officialJson = JsonInstant.encodeToString(JsonObject(official))

        val decoded = JsonInstant.decodeFromString<Settings>(officialJson)

        assertEquals(settings.providers.size, decoded.providers.size)
        assertEquals(settings.assistants.size, decoded.assistants.size)
        assertEquals(settings.chatModelId, decoded.chatModelId)
        assertTrue(decoded.providers.isNotEmpty())
    }

    @Test
    fun `official settings with legacy null model fields should deserialize`() {
        val settings = Settings()
        val jsonObj = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(settings)).jsonObject
        val official = jsonObj.toMutableMap().apply {
            officialMissingFields.forEach { remove(it) }
            // 官方旧版（titleModelId/suggestionModelId 已随 fastModel 重构移除）
            // 备份仍可能携带这些 null 字段：应作为未知键忽略而非解码失败
            put("titleModelId", kotlinx.serialization.json.JsonNull)
            put("suggestionModelId", kotlinx.serialization.json.JsonNull)
            put("selectedASRProviderId", kotlinx.serialization.json.JsonNull)
        }
        val decoded = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(JsonObject(official)))
        // 解码成功且核心字段保持默认（无标题/建议独立模型概念，回退 chatModel）
        assertEquals(settings.chatModelId, decoded.chatModelId)
        assertEquals(settings.fastModelId, decoded.fastModelId)
    }
}
