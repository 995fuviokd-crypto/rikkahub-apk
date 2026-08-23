package me.rerere.rikkahub.data.ai.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AcpMcpServersBuilderTest {

    private fun JsonElement.jsonArray(): JsonArray = this as JsonArray

    @Test
    fun `streamable http server converts to http transport`() {
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(
                name = "remote-api",
                headers = listOf("Authorization" to "Bearer abc"),
            ),
            url = "https://mcp.example.com/mcp",
        )
        val json = server.toAcpMcpServer().jsonObject
        assertEquals("http", json["type"]?.jsonPrimitive?.content)
        assertEquals("remote-api", json["name"]?.jsonPrimitive?.content)
        assertEquals("https://mcp.example.com/mcp", json["url"]?.jsonPrimitive?.content)
        val headers = json["headers"]!!.jsonArray()
        assertEquals(1, headers.size)
        assertEquals("Authorization", headers[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("Bearer abc", headers[0].jsonObject["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sse server converts to sse transport`() {
        val server = McpServerConfig.SseTransportServer(
            commonOptions = McpCommonOptions(name = "events"),
            url = "https://events.example.com/sse",
        )
        val json = server.toAcpMcpServer().jsonObject
        assertEquals("sse", json["type"]?.jsonPrimitive?.content)
        assertEquals("events", json["name"]?.jsonPrimitive?.content)
        assertEquals("https://events.example.com/sse", json["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `command server converts to stdio transport without type field`() {
        val server = McpServerConfig.CommandServerConfig(
            commonOptions = McpCommonOptions(name = "local-tool"),
            command = "node",
            args = listOf("server.js"),
            env = mapOf("NODE_ENV" to "production"),
        )
        val json = server.toAcpMcpServer().jsonObject
        assertEquals(null, json["type"])
        assertEquals("local-tool", json["name"]?.jsonPrimitive?.content)
        assertEquals("node", json["command"]?.jsonPrimitive?.content)
        val args = json["args"]!!.jsonArray()
        assertEquals(listOf("server.js"), args.map { it.jsonPrimitive.content })
        val env = json["env"]!!.jsonArray()
        assertEquals(1, env.size)
        assertEquals("NODE_ENV", env[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("production", env[0].jsonObject["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `blank name falls back to id`() {
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = ""),
            url = "https://mcp.example.com/mcp",
        )
        val json = server.toAcpMcpServer().jsonObject
        assertEquals(server.id.toString(), json["name"]?.jsonPrimitive?.content)
    }
}
