package me.rerere.ai.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class AcpNewSessionParamsTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `mcpServers serialize into session new request`() {
        val params = AcpNewSessionParams(
            cwd = "/workspace",
            mcpServers = buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "http")
                        put("name", "rikkahub-operit")
                        put("url", "http://127.0.0.1:48291/mcp")
                        put(
                            "headers",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "Authorization")
                                    put("value", "Bearer abc")
                                })
                            },
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "local-tool")
                        put("command", "node")
                        put("args", buildJsonArray { add(JsonPrimitive("server.js")) })
                    },
                )
            },
        )
        val encoded = json.encodeToString(AcpNewSessionParams.serializer(), params)
        val decoded = json.decodeFromString(AcpNewSessionParams.serializer(), encoded)
        assertEquals("/workspace", decoded.cwd)
        val servers = decoded.mcpServers
        assertEquals(2, servers.size)
        assertEquals("http", servers[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("rikkahub-operit", servers[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("http://127.0.0.1:48291/mcp", servers[0].jsonObject["url"]?.jsonPrimitive?.content)
        val headers = servers[0].jsonObject["headers"]!!.jsonArray
        assertEquals("Authorization", headers[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("local-tool", servers[1].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("node", servers[1].jsonObject["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun `mcpServers default to empty array when omitted`() {
        val params = AcpNewSessionParams(cwd = "/tmp")
        val encoded = json.encodeToString(AcpNewSessionParams.serializer(), params)
        val decoded = json.decodeFromString(AcpNewSessionParams.serializer(), encoded)
        assertEquals(0, decoded.mcpServers.size)
    }
}
