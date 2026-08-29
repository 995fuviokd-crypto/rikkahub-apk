package me.rerere.rikkahub.data.cordis

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.session.Session
import me.rerere.rikkahub.data.session.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CordisSeamAdaptersTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val modelId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val otherId = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private fun settingsWithModel(): Settings {
        val model = Model(modelId = "m1", id = modelId)
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        return Settings(
            providers = listOf(provider),
            assistants = listOf(Assistant(chatModelId = modelId)),
        )
    }

    // ---- resolveModel 纯函数 ----

    @Test
    fun `resolveModel uses config modelId when present`() {
        val settings = settingsWithModel()
        val config = buildJsonObject { put("modelId", modelId.toString()) }
        val model = resolveModel(config, settings)
        assertEquals(modelId, model?.id)
    }

    @Test
    fun `resolveModel falls back to current model when no modelId`() {
        val settings = settingsWithModel()
        val expected = settings.getCurrentChatModel()
        val model = resolveModel(buildJsonObject { }, settings)
        assertEquals(expected?.id, model?.id)
    }

    @Test
    fun `resolveModel falls back on invalid modelId`() {
        val settings = settingsWithModel()
        val expected = settings.getCurrentChatModel()
        val model = resolveModel(buildJsonObject { put("modelId", "not-a-uuid") }, settings)
        assertEquals(expected?.id, model?.id)
    }

    @Test
    fun `resolveModel falls back on unknown modelId`() {
        val settings = settingsWithModel()
        val expected = settings.getCurrentChatModel()
        val model = resolveModel(buildJsonObject { put("modelId", otherId.toString()) }, settings)
        assertEquals(expected?.id, model?.id)
    }

    // ---- HostSystemPromptSeam ----

    @Test
    fun `system prompt assembles fragments by position`() = runBlocking {
        val seam = HostSystemPromptSeam()
        seam.addFragment("a", position = 1) { "alpha" }
        seam.addFragment("b", position = 0) { "beta" }
        seam.addFragment("c", position = 2) { "gamma" }
        assertEquals("beta\n\nalpha\n\ngamma", seam.assemble())
    }

    @Test
    fun `system prompt removeFragment excludes fragment`() = runBlocking {
        val seam = HostSystemPromptSeam()
        seam.addFragment("a", position = 0) { "alpha" }
        seam.addFragment("b", position = 1) { "beta" }
        seam.removeFragment("a")
        assertEquals("beta", seam.assemble())
    }

    @Test
    fun `system prompt addFragment replaces same id`() = runBlocking {
        val seam = HostSystemPromptSeam()
        seam.addFragment("a", position = 0) { "old" }
        seam.addFragment("a", position = 0) { "new" }
        assertEquals("new", seam.assemble())
    }

    // ---- HostToolsSeam ----

    @Test
    fun `tools seam register get definitions`() {
        val seam = HostToolsSeam(CordisEventBus())
        val tool = ToolSeamDefinition(name = "echo", execute = { it })
        assertTrue(seam.register(tool))
        assertEquals(tool, seam.get("echo"))
        assertEquals(listOf("echo"), seam.definitions().map { it.name })
    }

    @Test
    fun `tools seam rejects duplicate name`() {
        val seam = HostToolsSeam(CordisEventBus())
        val tool = ToolSeamDefinition(name = "echo", execute = { it })
        assertTrue(seam.register(tool))
        assertFalse(seam.register(ToolSeamDefinition(name = "echo", execute = { it })))
        assertEquals(1, seam.definitions().size)
    }

    @Test
    fun `tools seam unregister removes tool`() {
        val seam = HostToolsSeam(CordisEventBus())
        val tool = ToolSeamDefinition(name = "echo", execute = { it })
        seam.register(tool)
        assertTrue(seam.unregister("echo"))
        assertNull(seam.get("echo"))
        assertFalse(seam.unregister("echo"))
    }

    @Test
    fun `tools seam notifies change event`() {
        val bus = CordisEventBus()
        val received = mutableListOf<String>()
        bus.on("tools/change") { event ->
            received += event.payload["count"]!!.jsonPrimitive.content
            null
        }
        val seam = HostToolsSeam(bus)
        seam.register(ToolSeamDefinition(name = "echo", execute = { it }))
        seam.register(ToolSeamDefinition(name = "sum", execute = { it }))
        assertTrue(received.isNotEmpty())
    }

    // ---- HostSessionsSeam ----

    @Test
    fun `sessions seam appends event and rebuilds context`() = runBlocking {
        val seam = HostSessionsSeam()
        val event = SessionEvent.UserMessage(seq = 1, time = 1000L, content = "hello")
        val asJson = json.encodeToJsonElement<SessionEvent>(event) as JsonObject
        seam.append(asJson)
        val ctx = seam.rebuildContext()
        assertEquals(1, ctx.size)
        assertEquals("hello", ctx[0].toText())
    }

    @Test
    fun `sessions seam bind snapshot`() = runBlocking {
        val seam = HostSessionsSeam()
        val initial = Session(listOf(SessionEvent.TurnStart(seq = 1, time = 1000L, turn = 0)))
        seam.bind(initial)
        assertEquals(1, seam.snapshot().length())
    }
}