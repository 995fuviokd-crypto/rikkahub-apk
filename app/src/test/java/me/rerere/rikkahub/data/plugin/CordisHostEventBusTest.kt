package me.rerere.rikkahub.data.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CordisHostEventBusTest {

    /** 事件总线绑定到 Dispatchers.Unconfined：emit 由调用线程同步处理，避免竞态。 */
    private fun newBus(): Pair<AppEventBus, CordisHostEventBus> {
        val appBus = AppEventBus()
        val bus = CordisHostEventBus(
            appBus,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        return appBus to bus
    }

    @Test
    fun `poll returns generation ended event with seq`() = runBlocking {
        val (appBus, bus) = newBus()
        val id = Uuid.random()
        appBus.tryEmit(AppEvent.ChatGenerationEnded(id, "sender", "preview text"))

        val events = bus.poll(0)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals("chat.generationEnded", e.type)
        assertEquals(id.toString(), e.payload["conversationId"]?.jsonPrimitive?.content)
        assertTrue("seq should be positive", e.seq > 0)
    }

    @Test
    fun `poll returns incremental events after since`() = runBlocking {
        val (appBus, bus) = newBus()
        appBus.tryEmit(AppEvent.ChatGenerationEnded(Uuid.random(), "a", "p1"))
        val first = bus.poll(0)
        assertEquals(1, first.size)
        val since = first[0].seq

        appBus.tryEmit(AppEvent.ChatGenerationEnded(Uuid.random(), "b", "p2"))
        val incremental = bus.poll(since)
        assertEquals(1, incremental.size)
        assertTrue("new seq should be greater", incremental[0].seq > since)
    }

    @Test
    fun `internal events are filtered out`() = runBlocking {
        val (appBus, bus) = newBus()
        appBus.tryEmit(AppEvent.Speak("hello"))
        appBus.tryEmit(AppEvent.OpenUsageAccessSettings)

        val events = bus.poll(0)
        // 内部事件不应进缓冲，且只暴露可感知类型
        assertTrue("no internal events should reach panels", events.isEmpty())
    }

    @Test
    fun `generation update carries text preview`() = runBlocking {
        val (appBus, bus) = newBus()
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("streamed hello")),
        )
        appBus.tryEmit(AppEvent.ChatGenerationUpdate(Uuid.random(), msg, "sender"))

        val events = bus.poll(0)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals("chat.generationUpdate", e.type)
        assertTrue("should contain streamed text", e.payload["text"]?.jsonPrimitive?.content?.contains("streamed hello") == true)
    }
}