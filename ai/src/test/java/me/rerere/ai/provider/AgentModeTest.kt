package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentModeTest {

    @Test
    fun `empty arguments resolve to null mode`() {
        val model = Model(modelId = "dsh")
        assertNull(model.agentMode())
    }

    @Test
    fun `unrelated arguments resolve to null mode`() {
        val model = Model(modelId = "dsh", agentArguments = listOf("--foo", "--bar"))
        assertNull(model.agentMode())
    }

    @Test
    fun `preset argument resolves to matching mode`() {
        val model = Model(
            modelId = "dsh",
            agentArguments = listOf("--agent-preset=code"),
        )
        assertEquals(AgentMode.CODE, model.agentMode())
    }

    @Test
    fun `setting mode appends preset argument`() {
        val model = Model(modelId = "dsh")
        val updated = model.withAgentMode(AgentMode.MINIMAL)
        assertEquals(listOf("--agent-preset=minimal"), updated.agentArguments)
        assertEquals(AgentMode.MINIMAL, updated.agentMode())
    }

    @Test
    fun `switching mode replaces existing preset argument`() {
        val model = Model(
            modelId = "dsh",
            agentArguments = listOf("--agent-preset=standard", "--verbose"),
        )
        val updated = model.withAgentMode(AgentMode.CORDIS)
        assertEquals(listOf("--verbose", "--agent-preset=cordis"), updated.agentArguments)
        assertEquals(AgentMode.CORDIS, updated.agentMode())
    }

    @Test
    fun `clearing mode removes preset argument`() {
        val model = Model(
            modelId = "dsh",
            agentArguments = listOf("--agent-preset=code", "--verbose"),
        )
        val updated = model.withAgentMode(null)
        assertEquals(listOf("--verbose"), updated.agentArguments)
        assertNull(updated.agentMode())
    }

    @Test
    fun `deepseek harness supports all four official presets`() {
        assertEquals(
            listOf(
                AgentMode.STANDARD,
                AgentMode.CODE,
                AgentMode.MINIMAL,
                AgentMode.CORDIS,
            ),
            AgentPlatform.DEEPSEEK_HARNESS.supportedModes,
        )
    }

    @Test
    fun `non mode platforms support no presets`() {
        assertEquals(emptyList<AgentMode>(), AgentPlatform.CODEX.supportedModes)
        assertEquals(emptyList<AgentMode>(), AgentPlatform.GEMINI_CLI.supportedModes)
        assertEquals(emptyList<AgentMode>(), AgentPlatform.ANTHROPIC_CLAUDE_CODE.supportedModes)
    }
}
