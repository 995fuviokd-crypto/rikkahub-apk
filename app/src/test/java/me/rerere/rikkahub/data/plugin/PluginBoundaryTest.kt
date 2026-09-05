package me.rerere.rikkahub.data.plugin

import me.rerere.rikkahub.data.plugin.PluginBoundary.getOrElse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D1.1 异常边界 + R2.4 能力预检（纯 JVM）。 */
class PluginBoundaryTest {

    @Test
    fun `guard ok passes value through`() {
        val r = PluginBoundary.guard("unit") { 42 }
        assertTrue(r is PluginBoundary.Result.Ok)
        assertEquals(42, (r as PluginBoundary.Result.Ok).value)
    }

    @Test
    fun `guard err wraps failure as unavailable`() {
        val r = PluginBoundary.guard("boom") { error("koin missing") }
        assertTrue(r is PluginBoundary.Result.Err)
        val err = (r as PluginBoundary.Result.Err).error
        assertTrue(err is PluginSubsystemError.Unavailable)
        assertEquals("boom", (err as PluginSubsystemError.Unavailable).what)
    }

    @Test
    fun `guardExecution err wraps failure as execution failed`() {
        val r = PluginBoundary.guardExecution("run") { throw IllegalStateException("js crash") }
        val err = (r as PluginBoundary.Result.Err).error
        assertTrue(err is PluginSubsystemError.ExecutionFailed)
    }

    @Test
    fun `getOrElse maps both branches`() {
        val ok: PluginBoundary.Result<Int> = PluginBoundary.guard("x") { 7 }
        assertEquals(7, ok.getOrElse { -1 })

        val err: PluginBoundary.Result<Int> = PluginBoundary.guard("x") { error("no") }
        assertEquals(-1, err.getOrElse { -1 })
    }

    // ---- PluginCapabilityPreflight（R2.4）----

    @Test
    fun `preflight parses cap tags and classifies support`() {
        val requested = PluginCapabilityPreflight.requestedFromTags(
            listOf("cap:llm", "cap:fs", "market", "cap:tools", "cap:")
        )
        assertEquals(listOf("llm", "fs", "tools"), requested)

        val result = PluginCapabilityPreflight.check(
            requested,
            hostCapabilities = setOf("llm", "tools", "sessions", "systemPrompt", "events"),
        )
        assertEquals(listOf("llm", "tools"), result.supported)
        assertEquals(listOf("fs"), result.unsupported)
        assertTrue("fs unsupported", !result.allSupported)
    }

    @Test
    fun `preflight all supported when within host capabilities`() {
        val result = PluginCapabilityPreflight.check(
            listOf("llm", "sessions"),
            hostCapabilities = setOf("llm", "tools", "sessions", "systemPrompt", "events"),
        )
        assertTrue(result.allSupported)
        assertTrue(result.unsupported.isEmpty())
    }

    @Test
    fun `preflight empty request is all supported`() {
        val result = PluginCapabilityPreflight.check(emptyList(), hostCapabilities = setOf("llm"))
        assertTrue(result.allSupported)
    }

    // ---- ScriptToolsSeamProducer 命名规则（R2.2/R4.4）----

    @Test
    fun `seam tool name is pluginId dot toolName`() {
        assertEquals(
            "dsh-market.fetchPage",
            ScriptToolsSeamProducer.seamToolName("dsh-market", "fetchPage"),
        )
    }
}
