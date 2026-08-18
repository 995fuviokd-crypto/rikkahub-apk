package me.rerere.rikkahub.data.ai.workflow

import me.rerere.rikkahub.data.model.AiStepConfig
import me.rerere.rikkahub.data.model.DelayStepConfig
import me.rerere.rikkahub.data.model.HttpStepConfig
import me.rerere.rikkahub.data.model.ShellStepConfig
import me.rerere.rikkahub.data.model.StepType
import me.rerere.rikkahub.data.model.TextStepConfig
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TemplateRendererTest {
    @Test
    fun `step output reference is replaced`() {
        val rendered = TemplateRenderer.render(
            template = "结果：{{step.1.output}}",
            stepOutputs = mapOf(1 to "hello"),
        )
        assertEquals("结果：hello", rendered)
    }

    @Test
    fun `input parameter reference is replaced`() {
        val rendered = TemplateRenderer.render(
            template = "用户：{{input.name}}",
            stepOutputs = emptyMap(),
            input = mapOf("name" to "张三"),
        )
        assertEquals("用户：张三", rendered)
    }

    @Test
    fun `missing step output becomes empty string`() {
        val rendered = TemplateRenderer.render(
            template = "a{{step.99.output}}b",
            stepOutputs = emptyMap(),
        )
        assertEquals("ab", rendered)
    }

    @Test
    fun `missing input parameter becomes empty string`() {
        val rendered = TemplateRenderer.render(
            template = "{{input.nope}}",
            stepOutputs = emptyMap(),
        )
        assertEquals("", rendered)
    }

    @Test
    fun `unmatched placeholder is preserved`() {
        val rendered = TemplateRenderer.render(
            template = "保留 {{foo}} 原样",
            stepOutputs = emptyMap(),
        )
        assertEquals("保留 {{foo}} 原样", rendered)
    }

    @Test
    fun `mixed references are resolved together`() {
        val rendered = TemplateRenderer.render(
            template = "{{step.2.output}} & {{input.key}}",
            stepOutputs = mapOf(2 to "s2"),
            input = mapOf("key" to "v"),
        )
        assertEquals("s2 & v", rendered)
    }
}

class WorkflowSerializationTest {
    @Test
    fun `workflow steps round-trip`() {
        val workflow = Workflow(
            id = "wf-1",
            name = "测试工作流",
            description = "desc",
            steps = listOf(
                WorkflowStep("s1", "文本", StepType.TEXT, TextStepConfig("{{input.name}}")),
                WorkflowStep("s2", "AI", StepType.AI, AiStepConfig("", "总结 {{step.1.output}}")),
                WorkflowStep("s3", "命令", StepType.SHELL, ShellStepConfig("echo hi", 5_000)),
                WorkflowStep("s4", "请求", StepType.HTTP, HttpStepConfig("POST", "https://x.com", mapOf("a" to "b"), "{}", 3_000)),
                WorkflowStep("s5", "延迟", StepType.DELAY, DelayStepConfig(2)),
            ),
        )
        val json = JsonInstant.encodeToString(workflow)
        val decoded = JsonInstant.decodeFromString<Workflow>(json)
        assertEquals(workflow, decoded)
        assertEquals(5, decoded.steps.size)
        assertEquals(StepType.SHELL, decoded.steps[2].type)
    }

    @Test
    fun `step list round-trip`() {
        val steps = listOf(
            WorkflowStep("a", "t", StepType.TEXT, TextStepConfig("x")),
            WorkflowStep("b", "d", StepType.DELAY, DelayStepConfig(5)),
        )
        val json = JsonInstant.encodeToString(steps)
        val decoded = JsonInstant.decodeFromString<List<WorkflowStep>>(json)
        assertEquals(steps, decoded)
    }

    @Test
    fun `unknown config type fails to decode`() {
        val bad = """[{"id":"x","name":"n","type":"TEXT","config":{"unknown":true}}]"""
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            JsonInstant.decodeFromString<List<WorkflowStep>>(bad)
        }
    }
}
