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
        "autoCompressThresholdTokens",
        "autoCompressKeepRecent",
        "autoReconnectEnabled",
        "autoReconnectMaxRetries",
        "multiRouteConcurrent",
        "screenResolutionOverrideEnabled",
        "screenResolutionOverrideWidth",
        "screenResolutionOverrideHeight",
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
    fun `official settings with null titleModelId should deserialize`() {
        val settings = Settings()
        val jsonObj = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(settings)).jsonObject
        val official = jsonObj.toMutableMap().apply {
            officialMissingFields.forEach { remove(it) }
            // 官方 titleModelId / suggestionModelId 可空，模拟 null
            put("titleModelId", kotlinx.serialization.json.JsonNull)
            put("suggestionModelId", kotlinx.serialization.json.JsonNull)
            put("selectedASRProviderId", kotlinx.serialization.json.JsonNull)
        }
        val decoded = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(JsonObject(official)))
        assertEquals(null, decoded.titleModelId)
        assertEquals(null, decoded.suggestionModelId)
    }
}
