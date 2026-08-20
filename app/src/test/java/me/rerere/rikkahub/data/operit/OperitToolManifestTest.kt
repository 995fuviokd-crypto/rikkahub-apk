package me.rerere.rikkahub.data.operit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OperitToolManifestTest {

    private fun metadataWithTools(): ByteArray {
        val meta = buildJsonObject {
            put("name", "chat-filter")
            put("display_name", buildJsonObject {
                put("en", "Chat Filter")
                put("zh", "聊天过滤")
            })
            put("description", buildJsonObject {
                put("en", "Filter chat messages")
            })
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("name", "list_chats")
                    put("description", "List all chats")
                })
                add(buildJsonObject {
                    put("name", "filter_message")
                    put("display_name", "过滤消息")
                    put("summary", "Filter a message")
                })
            }
        }
        return "/* METADATA $meta */\n".toByteArray() + "async function list_chats(p){return p;}".toByteArray()
    }

    @Test
    fun `tools from metadata extracts names and localized descriptions`() {
        val tools = OperitToolManifest.toolsFromMetadata(metadataWithTools())
        assertEquals(listOf("list_chats", "filter_message"), tools.map { it.name })
        assertEquals("List all chats", tools[0].description)
        assertEquals("Filter a message", tools[1].description)
    }

    @Test
    fun `tools from directory aggregates js files`() {
        val dir = kotlin.io.path.createTempDirectory("operit-manifest").toFile()
        try {
            val script = metadataWithTools()
            File(dir, "a.js").writeBytes(script)
            File(dir, "b.js").writeBytes(script)
            val tools = OperitToolManifest.toolsFromDirectory(dir)
            // distinctBy name dedupes identical tool names
            assertEquals(listOf("list_chats", "filter_message"), tools.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `build and parse json round trips`() {
        val data = OperitToolManifestData(
            name = "pkg",
            description = "desc",
            tools = listOf(
                OperitToolDef("a", "A tool"),
                OperitToolDef("b"),
            )
        )
        val json = OperitToolManifest.buildJson(data)
        val parsed = OperitToolManifest.parseJson(json)
        assertEquals(data.name, parsed?.name)
        assertEquals(2, parsed?.tools?.size)
        assertEquals("A tool", parsed?.tools?.first()?.description)
    }

    @Test
    fun `describe system prompt mentions run_operit_tool when tools exist`() {
        val prompt = OperitToolManifest.describeSystemPrompt(
            name = "video-parse",
            description = "Parse videos",
            tools = listOf(OperitToolDef("parse", "Parse a video URL")),
        )
        assertTrue(prompt.contains("video-parse"))
        assertTrue(prompt.contains("run_operit_tool"))
        assertTrue(prompt.contains("parse"))
    }

    @Test
    fun `tools in json manifest use array format`() {
        val data = OperitToolManifestData(
            name = "x",
            tools = listOf(OperitToolDef("t1", "d1")),
        )
        val json = OperitToolManifest.buildJson(data)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        assertTrue(root["tools"] is kotlinx.serialization.json.JsonArray)
    }
}
