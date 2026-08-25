package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 跨端备份导入清洗验证 (Robolectric):
 * PC 端备份可能包含移动端不支持的模态/判别符, 清洗后 Settings 应可正常反序列化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsJsonSanitizerTest {

    private fun modalityList(json: String, path: String): List<String> {
        val root = JsonInstant.parseToJsonElement(SettingsJsonSanitizer.sanitize(json)).jsonObject
        return path.split(".").runningFold(root as kotlinx.serialization.json.JsonElement) { acc, key ->
            when (acc) {
                is kotlinx.serialization.json.JsonObject -> acc[key]!!
                is kotlinx.serialization.json.JsonArray -> acc[key.toInt()]
                else -> error("bad path")
            }
        }.last().jsonArray.map { it.jsonPrimitive.content }
    }

    @Test
    fun `strips incompatible modalities from PC backup`() {
        val json = """
            {"providers":[{"type":"openai","models":[{"modelId":"gpt-audio",
              "inputModalities":["TEXT","AUDIO","VIDEO","DOCUMENT"],
              "outputModalities":["IMAGE","DOCUMENT"]}]}]}
        """.trimIndent()
        assertEquals(listOf("TEXT"), modalityList(json, "providers.0.models.0.inputModalities"))
        assertEquals(listOf("IMAGE"), modalityList(json, "providers.0.models.0.outputModalities"))
    }

    @Test
    fun `falls back to TEXT when all modalities are incompatible`() {
        val json = """
            {"providers":[{"type":"google","models":[{"modelId":"gemini-x",
              "inputModalities":["AUDIO"],"outputModalities":["AUDIO"]}]}]}
        """.trimIndent()
        assertEquals(listOf("TEXT"), modalityList(json, "providers.0.models.0.inputModalities"))
    }

    @Test
    fun `drops providers with unknown discriminator`() {
        val json = """
            {"providers":[{"type":"openai","name":"OpenAI"},{"type":"mystery_provider","name":"?"}]}
        """.trimIndent()
        val out = JsonInstant.parseToJsonElement(SettingsJsonSanitizer.sanitize(json)).jsonObject
        assertEquals(1, out["providers"]!!.jsonArray.size)
    }

    @Test
    fun `filters unknown builtin tools and model abilities`() {
        val json = """
            {"providers":[{"type":"openai","models":[{"modelId":"m",
              "abilities":["TOOL","REASONING","FUTURE_ABILITY"],
              "tools":[{"type":"search"},{"type":"future_tool"}]}]}]}
        """.trimIndent()
        assertEquals(
            listOf("TOOL", "REASONING"),
            modalityList(json, "providers.0.models.0.abilities"),
        )
        val root = JsonInstant.parseToJsonElement(SettingsJsonSanitizer.sanitize(json)).jsonObject
        val tools = root["providers"]!!.jsonArray[0].jsonObject["models"]!!.jsonArray[0]
            .jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `maps PC avatar short names and falls back to Dummy for unknown`() {
        val json = """
            {"assistants":[
              {"name":"a","avatar":{"type":"emoji","content":"X"}},
              {"name":"b","avatar":{"type":"weird_kind"}},
              {"name":"c","avatar":{"type":"me.rerere.rikkahub.data.model.Avatar.Dummy"}}
            ]}
        """.trimIndent()
        val out = JsonInstant.parseToJsonElement(SettingsJsonSanitizer.sanitize(json)).jsonObject
        val assistants = out["assistants"]!!.jsonArray
        assertEquals(
            "me.rerere.rikkahub.data.model.Avatar.Emoji",
            assistants[0].jsonObject["avatar"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "me.rerere.rikkahub.data.model.Avatar.Dummy",
            assistants[1].jsonObject["avatar"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        // Android 原生 FQN 形态保持不变
        assertEquals(
            "me.rerere.rikkahub.data.model.Avatar.Dummy",
            assistants[2].jsonObject["avatar"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `filters unknown message parts and annotations in preset messages`() {
        val json = """
            {"assistants":[{"presetMessages":[
              {"role":"user",
               "parts":[{"type":"loading"},{"type":"text","text":"hi"}],
               "annotations":[{"type":"url_citation","url":"https://x"},{"type":"model_call_error"}]}
            ]}]}
        """.trimIndent()
        val out = JsonInstant.parseToJsonElement(SettingsJsonSanitizer.sanitize(json)).jsonObject
        val message = out["assistants"]!!.jsonArray[0].jsonObject["presetMessages"]!!.jsonArray[0].jsonObject
        val parts = message["parts"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        val annotations = message["annotations"]!!.jsonArray
        assertEquals(1, annotations.size)
        assertEquals("url_citation", annotations[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `filters unknown search services and relocates selection index`() {
        val json = """
            {"searchServices":[{"type":"tavily"},{"type":"future_engine"},{"type":"exa"}],
             "searchServiceSelected":2}
        """.trimIndent()
        val out = JsonInstant.parseToJsonElement(SettingsJsonSanitizer.sanitize(json)).jsonObject
        val services = out["searchServices"]!!.jsonArray
        assertEquals(listOf("tavily", "exa"), services.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(1, out["searchServiceSelected"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `clean backup passes through unchanged`() {
        val json = """
            {"providers":[{"type":"openai","apiKey":"sk-test","baseUrl":"https://api.openai.com/v1",
              "models":[{"modelId":"gpt-4o","displayName":"GPT-4o",
                "inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT","IMAGE"],
                "abilities":["TOOL","REASONING"]}]}],
             "searchServices":[{"type":"tavily"}],"searchServiceSelected":0}
        """.trimIndent()
        assertEquals(JsonInstant.parseToJsonElement(json), JsonInstant.parseToJsonElement(SettingsJsonSanitizer.sanitize(json)))
    }

    @Test
    fun `invalid json returns original string`() {
        val broken = "{not valid json"
        assertEquals(broken, SettingsJsonSanitizer.sanitize(broken))
    }

    @Test
    fun `end to end - dirty PC backup decodes into Settings after sanitize`() {
        val dirtyBackupJson = """
            {"providers":[
              {"type":"openai","apiKey":"sk-pc","baseUrl":"https://api.openai.com/v1","models":[
                {"modelId":"gpt-4o-audio","inputModalities":["TEXT","AUDIO"],"outputModalities":["TEXT"]},
                {"modelId":"gpt-4o","inputModalities":["TEXT"],"outputModalities":["TEXT"]}
              ]},
              {"type":"pc_only_provider","name":"Mystery"}
            ],
             "assistants":[{"name":"A","avatar":{"type":"emoji","content":"R"}}],
             "searchServices":[{"type":"tavily"},{"type":"future_engine"}],"searchServiceSelected":1}
        """.trimIndent()

        val settings = JsonInstant.decodeFromString<Settings>(SettingsJsonSanitizer.sanitize(dirtyBackupJson))

        assertEquals(1, settings.providers.size)
        assertTrue(settings.providers[0] is ProviderSetting.OpenAI)
        assertEquals(2, settings.providers[0].models.size)
        assertEquals(listOf(me.rerere.ai.provider.Modality.TEXT), settings.providers[0].models[0].inputModalities)
        // 原选中 future_engine (下标 1) 被过滤, 按 PC 端契约回退到 0
        assertEquals(0, settings.searchServiceSelected)
        assertEquals(1, settings.searchServices.size)
    }
}
