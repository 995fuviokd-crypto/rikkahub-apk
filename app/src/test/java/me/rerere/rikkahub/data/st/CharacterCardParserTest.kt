package me.rerere.rikkahub.data.st

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardParserTest {

    private fun parse(text: String) = CharacterCardParser.parse(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun `V2 卡解析 名称 系统提示 开场白`() {
        val card = parse(
            """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Aria",
                "description": "A forest mage",
                "personality": "calm",
                "scenario": "an ancient grove",
                "first_mes": "Welcome, traveler.",
                "system_prompt": "Stay in character."
              }
            }
            """.trimIndent(),
        )
        val a = card.assistant
        assertEquals("Aria", a.name)
        assertTrue(a.systemPrompt.contains("Aria"))
        assertTrue(a.systemPrompt.contains("A forest mage"))
        assertTrue(a.systemPrompt.contains("Stay in character."))
        assertEquals(1, a.presetMessages.size)
        assertTrue(a.presetMessages[0].parts.first().toString().contains("Welcome"))
    }

    @Test
    fun `V1 扁平卡无 data 包裹也能解析`() {
        val card = parse(
            """
            {
              "name": "Kael",
              "description": "A knight",
              "personality": "brave",
              "scenario": "the fallen kingdom",
              "first_mes": "Halt! Who goes there?"
            }
            """.trimIndent(),
        )
        val a = card.assistant
        assertEquals("Kael", a.name)
        assertTrue(a.systemPrompt.contains("A knight"))
        assertEquals(1, a.presetMessages.size)
        assertNull(card.lorebook)
    }

    @Test
    fun `character_book 映射为世界书条目`() {
        val card = parse(
            """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Aria",
                "description": "mage",
                "first_mes": "hi",
                "character_book": {
                  "name": "grove lore",
                  "entries": [
                    {
                      "keys": ["forest", "woods"],
                      "content": "The forest whispers at night.",
                      "comment": "Forest lore",
                      "constant": false,
                      "disable": false,
                      "case_sensitive": true,
                      "insertion_order": 10
                    },
                    {
                      "keys": ["crown"],
                      "content": "The crown of thorns rules the grove.",
                      "constant": true,
                      "disable": false
                    },
                    { "keys": [], "content": "" }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val lorebook = card.lorebook
        assertNotNull(lorebook)
        assertEquals("Aria", lorebook!!.name)
        assertEquals(3, lorebook.entries.size)

        val e1 = lorebook.entries[0] as PromptInjection.RegexInjection
        assertEquals(listOf("forest", "woods"), e1.keywords)
        assertEquals("The forest whispers at night.", e1.content)
        assertEquals("Forest lore", e1.name)
        assertTrue(e1.caseSensitive)
        assertTrue(!e1.constantActive)

        val e2 = lorebook.entries[1] as PromptInjection.RegexInjection
        assertTrue(e2.constantActive)
    }

    @Test
    fun `缺少 name 抛出 ParseException`() {
        val ex = runCatching {
            parse("""{"spec":"chara_card_v2","data":{"description":"no name"}}""")
        }.exceptionOrNull()
        assertTrue(ex is CharacterCardParser.ParseException)
    }

    @Test
    fun `无世界书的卡 lorebook 为 null`() {
        val card = parse(
            """
            {"spec":"chara_card_v2","data":{"name":"Solo","description":"d","first_mes":"f"}}
            """.trimIndent(),
        )
        assertNotNull(card.assistant.systemPrompt)
        assertNull(card.lorebook)
    }
}
