package me.rerere.rikkahub.data.tavern

import me.rerere.rikkahub.data.model.InjectionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernCardConverterTest {

    @Test
    fun `parses chara_card_v2 spec`() {
        val json = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "Alice",
            "description": "A test character",
            "personality": "calm",
            "scenario": "in a lab",
            "first_mes": "Hello!",
            "mes_example": "<START>\n{{user}}: hi\n{{char}}: hello",
            "system_prompt": "Stay in character.",
            "tags": ["test", "demo"],
            "emoji": "🧪",
            "character_book": {
              "entries": [
                {"keys": ["magic"], "content": "Magic is real in this world.", "comment": "magic rule", "constant": true}
              ]
            }
          }
        }
        """.trimIndent()

        val card = TavernCardConverter.parseCard(json)
        assertEquals("Alice", card.name)
        assertEquals("A test character", card.description)
        assertEquals("calm", card.personality)
        assertEquals("in a lab", card.scenario)
        assertEquals("Hello!", card.firstMes)
        assertEquals(listOf("test", "demo"), card.tags)
        assertEquals("🧪", card.emoji)
        assertEquals(1, card.characterBookEntries.size)
        assertEquals(listOf("magic"), card.characterBookEntries[0].keys)
        assertTrue(card.characterBookEntries[0].constant)
    }

    @Test
    fun `parses v1 legacy top-level format`() {
        val json = """
        {"char_name": "Bob", "description": "old card", "personality": "grumpy", "first_mes": "yo"}
        """.trimIndent()
        val card = TavernCardConverter.parseCard(json)
        assertEquals("Bob", card.name)
        assertEquals("old card", card.description)
        assertEquals("yo", card.firstMes)
        assertTrue(TavernCardConverter.isCharacterCardJson(json))
    }

    @Test
    fun `toAssistant builds roleplay prompt and preset message`() {
        val card = TavernCardConverter.parseCard(
            """
            {"spec":"chara_card_v2","data":{"name":"Alice","description":"D","personality":"P","scenario":"S","first_mes":"FM","mes_example":"EX"}}
            """.trimIndent()
        )
        val assistant = TavernCardConverter.toAssistant(card)
        assertEquals("Alice", assistant.name)
        assertTrue(assistant.systemPrompt.contains("You are roleplaying as Alice."))
        assertTrue(assistant.systemPrompt.contains("## Description of the character"))
        assertTrue(assistant.systemPrompt.contains("## Personality of the character"))
        assertTrue(assistant.systemPrompt.contains("## Scenario"))
        assertTrue(assistant.systemPrompt.contains("<START>"))
        assertEquals(1, assistant.presetMessages.size)
        assertEquals("FM", (assistant.presetMessages[0].parts.first() as? me.rerere.ai.ui.UIMessagePart.Text)?.text)
    }

    @Test
    fun `cardToLorebook maps entries with position and keywords`() {
        val card = TavernCardConverter.parseCard(
            """
            {"spec":"chara_card_v2","data":{"name":"W","character_book":{"entries":[
              {"keys":["dragon"],"content":"Dragons fear gold.","position":4,"extensions":{"depth":2}},
              {"keys":["king"],"content":"The king is dead.","position":0,"enabled":false}
            ]}}}
            """.trimIndent()
        )
        val book = TavernCardConverter.cardToLorebook(card)
        assertNotNull(book)
        assertEquals(2, book!!.entries.size)
        val dragon = book.entries[0]
        assertEquals(listOf("dragon"), dragon.keywords)
        assertEquals(InjectionPosition.AT_DEPTH, dragon.position)
        assertEquals(2, dragon.injectDepth)
        assertTrue(dragon.enabled)
        val king = book.entries[1]
        assertEquals(InjectionPosition.BEFORE_SYSTEM_PROMPT, king.position)
        assertTrue(!king.enabled)
    }

    @Test
    fun `parses sillytavern world info map entries`() {
        val worldJson = """
        {
          "entries": {
            "0": {"key": ["forest"], "content": "Dark forest ahead.", "disable": false, "order": 100,
                  "position": 1, "depth": 4, "caseSensitive": false, "scanDepth": 3, "constant": false},
            "1": {"key": ["city"], "content": "Neon everywhere.", "disable": true}
          }
        }
        """.trimIndent()
        val book = TavernCardConverter.parseWorldInfo(worldJson, "my_world.json")
        assertEquals("my_world", book.name)
        assertEquals(2, book.entries.size)
        val forest = book.entries.first { it.keywords == listOf("forest") }
        assertTrue(forest.enabled)
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, forest.position)
        assertEquals(3, forest.scanDepth)
        val city = book.entries.first { it.keywords == listOf("city") }
        assertTrue(!city.enabled)
    }

    @Test
    fun `parses sillytavern preset sampling params`() {
        val presetJson = """
        {"name":"My Preset","temperature":0.7,"top_p":0.9,"openai_max_tokens":1024,
         "frequency_penalty":0.1,"presence_penalty":-0.1}
        """.trimIndent()
        val preset = TavernCardConverter.parsePreset(presetJson)
        assertEquals("My Preset", preset.name)
        assertEquals(0.7f, preset.temperature!!, 1e-6f)
        assertEquals(0.9f, preset.topP!!, 1e-6f)
        assertEquals(1024, preset.maxTokens)
    }

    @Test
    fun `parses regex script single and library forms`() {
        val single = """
        {"scriptName":"Trim quotes","findRegex":"/\u0022([^\u0022]*)\u0022/g","replaceString":"$1",
         "placement":[2],"disabled":false,"markdownOnly":true}
        """.trimIndent()
        val one = TavernCardConverter.parseRegexScripts(single)
        assertEquals(1, one.size)
        assertEquals("Trim quotes", one[0].name)
        assertTrue(one[0].affectingScope.contains(me.rerere.rikkahub.data.model.AssistantAffectScope.ASSISTANT))
        assertTrue(one[0].visualOnly)

        val lib = """
        [{"scripts":[{"scriptName":"A","findRegex":"foo","replaceString":"bar","placement":[1]}]},
         {"scriptName":"B","findRegex":"x","replaceString":"y","placement":[1,2]}]
        """.trimIndent()
        val many = TavernCardConverter.parseRegexScripts(lib)
        assertEquals(2, many.size)
        assertEquals(setOf(me.rerere.rikkahub.data.model.AssistantAffectScope.USER), many[0].affectingScope)
    }

    @Test
    fun `parses tavern market index json`() {
        val index = """
        [
          {"id":"socrates","name":"苏格拉底","description":"提问教学","tags":["教育"],"emoji":"🏛️","file":"tavern/socrates.json"},
          {"id":"broken"},
          {"name":"no id"}
        ]
        """.trimIndent()
        val list = me.rerere.rikkahub.data.api.TavernMarketDataSource.parseIndexJson(index)
        assertEquals(1, list.size)
        assertEquals("socrates", list[0].id)
        assertEquals("苏格拉底", list[0].name)
        assertEquals("tavern/socrates.json", list[0].file)
        assertEquals("🏛️", list[0].emoji)
    }
}
