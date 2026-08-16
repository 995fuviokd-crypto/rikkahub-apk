package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.model.RouterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskModeRouterTransformerTest {

    @Test
    fun `planning keywords should classify as SPEC`() {
        assertEquals(RouterMode.SPEC, TaskModeRouterTransformer.classifyTask("帮我制定一个详细的计划"))
        assertEquals(RouterMode.SPEC, TaskModeRouterTransformer.classifyTask("请设计一个系统架构"))
        assertEquals(RouterMode.SPEC, TaskModeRouterTransformer.classifyTask("compare these two options"))
        assertEquals(RouterMode.SPEC, TaskModeRouterTransformer.classifyTask("summarize the key findings"))
    }

    @Test
    fun `execution keywords should classify as REACT`() {
        assertEquals(RouterMode.REACT, TaskModeRouterTransformer.classifyTask("请实现一个登录功能"))
        assertEquals(RouterMode.REACT, TaskModeRouterTransformer.classifyTask("write a function to parse json"))
        assertEquals(RouterMode.REACT, TaskModeRouterTransformer.classifyTask("deploy the service"))
        assertEquals(RouterMode.REACT, TaskModeRouterTransformer.classifyTask("build a new feature"))
        assertEquals(RouterMode.REACT, TaskModeRouterTransformer.classifyTask("开发一个游戏"))
    }

    @Test
    fun `fix keywords should classify as SPEC plan-first`() {
        assertEquals(RouterMode.SPEC, TaskModeRouterTransformer.classifyTask("修复这个 bug"))
        assertEquals(RouterMode.SPEC, TaskModeRouterTransformer.classifyTask("fix the broken parser"))
        assertEquals(RouterMode.SPEC, TaskModeRouterTransformer.classifyTask("排查一下报错"))
        assertEquals(RouterMode.SPEC, TaskModeRouterTransformer.classifyTask("重构这个模块"))
    }

    @Test
    fun `no keywords should classify as WEAK`() {
        assertEquals(RouterMode.WEAK, TaskModeRouterTransformer.classifyTask("你好"))
        assertEquals(RouterMode.WEAK, TaskModeRouterTransformer.classifyTask("hello"))
        assertEquals(RouterMode.WEAK, TaskModeRouterTransformer.classifyTask(""))
    }

    @Test
    fun `mixed keywords should prefer SPEC when equal`() {
        assertEquals(RouterMode.SPEC, TaskModeRouterTransformer.classifyTask("先分析需求，再编写代码"))
    }

    @Test
    fun `guide for SPEC should mention planning`() {
        val guide = TaskModeRouterTransformer.guideFor(RouterMode.SPEC, isReasoningModel = false)
        assertTrue(guide.contains("[task-routing]"))
        assertTrue(guide.contains("planning task"))
    }

    @Test
    fun `guide for REACT should mention execution`() {
        val guide = TaskModeRouterTransformer.guideFor(RouterMode.REACT, isReasoningModel = false)
        assertTrue(guide.contains("[task-routing]"))
        assertTrue(guide.contains("execution task"))
    }

    @Test
    fun `guide for WEAK should let the model decide`() {
        val guide = TaskModeRouterTransformer.guideFor(RouterMode.WEAK, isReasoningModel = false)
        assertTrue(guide.contains("Judge the task yourself"))
    }

    @Test
    fun `guide for AUTO should be empty`() {
        assertEquals("", TaskModeRouterTransformer.guideFor(RouterMode.AUTO, isReasoningModel = false))
    }

    @Test
    fun `reasoning model should get deeper guidance`() {
        val withDepth = TaskModeRouterTransformer.guideFor(RouterMode.SPEC, isReasoningModel = true)
        val withoutDepth = TaskModeRouterTransformer.guideFor(RouterMode.SPEC, isReasoningModel = false)
        assertTrue(withDepth.contains("Think through the approach before acting"))
        assertFalse(withoutDepth.contains("Think through the approach"))
    }

    @Test
    fun `deepseek models are detected by model id`() {
        assertTrue(TaskModeRouterTransformer.isDeepSeekModel("deepseek-v4-flash"))
        assertTrue(TaskModeRouterTransformer.isDeepSeekModel("deepseek-v4-pro"))
        assertTrue(TaskModeRouterTransformer.isDeepSeekModel("deepseek-chat"))
        assertTrue(TaskModeRouterTransformer.isDeepSeekModel("DeepSeek-V4-Pro"))
        assertTrue(TaskModeRouterTransformer.isDeepSeekModel("deep-seek-r1"))
        assertTrue(TaskModeRouterTransformer.isDeepSeekModel("ds-chat"))
        assertTrue(TaskModeRouterTransformer.isDeepSeekModel("ds/v4"))
        assertTrue(TaskModeRouterTransformer.isDeepSeekModel("gpt-oss-20b-seek"))
        assertFalse(TaskModeRouterTransformer.isDeepSeekModel("gpt-4o"))
        assertFalse(TaskModeRouterTransformer.isDeepSeekModel("claude-sonnet-4"))
        assertFalse(TaskModeRouterTransformer.isDeepSeekModel(""))
        assertFalse(TaskModeRouterTransformer.isDeepSeekModel(null))
    }

    @Test
    fun `flash model is detected by model id`() {
        assertTrue(TaskModeRouterTransformer.isFlashModel("deepseek-v4-flash"))
        assertTrue(TaskModeRouterTransformer.isFlashModel("DeepSeek-V4-Flash"))
        assertTrue(TaskModeRouterTransformer.isFlashModel("deepseek-chat-flash"))
        assertFalse(TaskModeRouterTransformer.isFlashModel("deepseek-v4-pro"))
        assertFalse(TaskModeRouterTransformer.isFlashModel(null))
    }

    @Test
    fun `weak guide differs between pro and flash persona`() {
        val pro = TaskModeRouterTransformer.guideFor(RouterMode.WEAK, isReasoningModel = false, modelId = "deepseek-v4-pro")
        val flash = TaskModeRouterTransformer.guideFor(RouterMode.WEAK, isReasoningModel = false, modelId = "deepseek-v4-flash")
        assertTrue(pro.contains("Judge the task yourself"))
        assertTrue(flash.contains("Judge the task yourself"))
        assertTrue(flash.contains("review what you have already done"))
        assertTrue(flash.contains("Do not run environment checks"))
        assertFalse(pro.contains("review what you have already done"))
    }

    @Test
    fun `pro and flash weak guides both classify build or fix`() {
        val pro = TaskModeRouterTransformer.guideFor(RouterMode.WEAK, isReasoningModel = false, modelId = "deepseek-v4-pro")
        val flash = TaskModeRouterTransformer.guideFor(RouterMode.WEAK, isReasoningModel = false, modelId = "deepseek-v4-flash")
        assertTrue(pro.contains("build or a fix"))
        assertTrue(flash.contains("build or a fix"))
    }

    @Test
    fun `complex tasks are detected by length or architecture wording`() {
        assertTrue(TaskModeRouterTransformer.isComplexTask("x".repeat(200)))
        assertTrue(TaskModeRouterTransformer.isComplexTask("请重构整个系统的架构并给出优化方案"))
        assertTrue(TaskModeRouterTransformer.isComplexTask("refactor the system architecture comprehensively"))
        assertFalse(TaskModeRouterTransformer.isComplexTask("写一个 hello world"))
        assertFalse(TaskModeRouterTransformer.isComplexTask("修复这个 bug"))
    }

    @Test
    fun `we need anchor is appended for deepseek auto enabled`() {
        val guide = TaskModeRouterTransformer.guideFor(RouterMode.REACT, isReasoningModel = false)
        val anchored = TaskModeRouterTransformer.withWeNeedAnchor(guide, autoEnabled = true)
        assertTrue(anchored.contains("We need"))
        assertTrue(anchored.contains("shared goal"))
        assertTrue(anchored.startsWith(guide))
    }

    @Test
    fun `we need anchor is not appended when not auto enabled`() {
        val guide = TaskModeRouterTransformer.guideFor(RouterMode.REACT, isReasoningModel = false)
        val plain = TaskModeRouterTransformer.withWeNeedAnchor(guide, autoEnabled = false)
        assertEquals(guide, plain)
        assertFalse(plain.contains("We need"))
    }

    @Test
    fun `we need anchor keeps exact wording stable for prompt cache`() {
        val guide = TaskModeRouterTransformer.guideFor(RouterMode.SPEC, isReasoningModel = false)
        val a = TaskModeRouterTransformer.withWeNeedAnchor(guide, autoEnabled = true)
        val b = TaskModeRouterTransformer.withWeNeedAnchor(guide, autoEnabled = true)
        assertEquals(a, b)
    }
}
