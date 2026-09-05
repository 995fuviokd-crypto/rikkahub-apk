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
        // D1.1：构造期零副作用，须显式启动收集协程
        bus.start()
        return appBus to bus
    }

    @Test
    fun `not started bus collects nothing`() = runBlocking {
        val appBus = AppEventBus()
        val bus = CordisHostEventBus(
            appBus,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        appBus.tryEmit(AppEvent.ChatGenerationEnded(Uuid.random(), "a", "p"))
        assertTrue("unstarted bus should buffer nothing", bus.poll(0).isEmpty())
    }

    @Test
    fun `start is idempotent`() = runBlocking {
        val (appBus, bus) = newBus()
        bus.start()
        appBus.tryEmit(AppEvent.ChatGenerationEnded(Uuid.random(), "a", "p1"))
        val events = bus.poll(0)
        assertEquals("each event buffered exactly once", 1, events.size)
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

    // ---- R3.2 推送订阅（subscribe/unsubscribe）----

    @Test
    fun `subscribe pushes matched events to handler`() = runBlocking {
        val (appBus, bus) = newBus()
        val received = mutableListOf<CordisHostEventBus.CordisEvent>()
        bus.subscribe("p1", setOf("chat.")) { received += it }

        appBus.tryEmit(AppEvent.ChatGenerationEnded(Uuid.random(), "a", "preview"))

        assertEquals("handler should receive exactly one event", 1, received.size)
        assertEquals("chat.generationEnded", received[0].type)
        assertTrue("seq should be assigned", received[0].seq > 0)
        // 推送与拉取共享同一缓冲语义：poll 也能拿到同一事件
        assertEquals(1, bus.poll(0).size)
    }

    @Test
    fun `topic prefix filters unmatched events`() = runBlocking {
        val (appBus, bus) = newBus()
        val received = mutableListOf<CordisHostEventBus.CordisEvent>()
        bus.subscribe("p1", setOf("chat.generationEnded")) { received += it }

        // 只订阅了 generationEnded：generationUpdate 不应推送
        appBus.tryEmit(
            AppEvent.ChatGenerationUpdate(
                Uuid.random(),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("x"))),
                "s",
            )
        )
        assertTrue("unmatched topic should not be pushed", received.isEmpty())

        appBus.tryEmit(AppEvent.ChatGenerationEnded(Uuid.random(), "a", "p"))
        assertEquals("matched topic should be pushed", 1, received.size)
    }

    @Test
    fun `resubscribe replaces previous subscription`() = runBlocking {
        val (appBus, bus) = newBus()
        val first = mutableListOf<CordisHostEventBus.CordisEvent>()
        val second = mutableListOf<CordisHostEventBus.CordisEvent>()
        bus.subscribe("p1", setOf("chat.")) { first += it }
        bus.subscribe("p1", setOf("chat.")) { second += it }

        appBus.tryEmit(AppEvent.ChatGenerationEnded(Uuid.random(), "a", "p"))

        assertTrue("replaced subscription must not receive", first.isEmpty())
        assertEquals("latest subscription receives", 1, second.size)
    }

    @Test
    fun `unsubscribe stops push but keeps buffer`() = runBlocking {
        val (appBus, bus) = newBus()
        val received = mutableListOf<CordisHostEventBus.CordisEvent>()
        val sub = bus.subscribe("p1", setOf("chat.")) { received += it }
        bus.unsubscribe(sub)

        appBus.tryEmit(AppEvent.ChatGenerationEnded(Uuid.random(), "a", "p"))

        assertTrue("unsubscribed handler must not receive", received.isEmpty())
        // 断线恢复语义：缓冲仍可用 poll 拉取
        assertEquals("buffer keeps event for recovery poll", 1, bus.poll(0).size)
    }

    @Test
    fun `handler exception does not break buffer or other subscribers`() = runBlocking {
        val (appBus, bus) = newBus()
        val healthy = mutableListOf<CordisHostEventBus.CordisEvent>()
        bus.subscribe("boom", setOf("chat.")) { throw IllegalStateException("bad handler") }
        bus.subscribe("ok", setOf("chat.")) { healthy += it }

        appBus.tryEmit(AppEvent.ChatGenerationEnded(Uuid.random(), "a", "p"))

        assertEquals("healthy subscriber still receives", 1, healthy.size)
        assertEquals("buffer unaffected by handler failure", 1, bus.poll(0).size)
    }
}