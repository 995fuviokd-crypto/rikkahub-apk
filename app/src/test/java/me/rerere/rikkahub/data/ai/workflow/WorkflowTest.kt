package me.rerere.rikkahub.data.ai.workflow

import me.rerere.rikkahub.data.model.AiStepConfig
import me.rerere.rikkahub.data.model.DelayStepConfig
import me.rerere.rikkahub.data.model.EndStepConfig
import me.rerere.rikkahub.data.model.ExecutionStatus
import me.rerere.rikkahub.data.model.ExtractMode
import me.rerere.rikkahub.data.model.ExtractStepConfig
import me.rerere.rikkahub.data.model.ForStepConfig
import me.rerere.rikkahub.data.model.HttpStepConfig
import me.rerere.rikkahub.data.model.IfStepConfig
import me.rerere.rikkahub.data.model.MergeStepConfig
import me.rerere.rikkahub.data.model.NodeType
import me.rerere.rikkahub.data.model.OutputStepConfig
import me.rerere.rikkahub.data.model.ShellStepConfig
import me.rerere.rikkahub.data.model.StartStepConfig
import me.rerere.rikkahub.data.model.StepType
import me.rerere.rikkahub.data.model.TextStepConfig
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowEdge
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowNode
import me.rerere.rikkahub.data.model.WorkflowStats
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.rikkahub.data.model.legacyStepsToGraph
import me.rerere.rikkahub.data.model.nodeReferenceIds
import me.rerere.rikkahub.data.model.topologicalOrder
import me.rerere.rikkahub.data.model.validate
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

class WorkflowGraphTest {
    private fun node(id: String, type: NodeType, name: String = id) =
        WorkflowNode(id = id, type = type, name = name)

    @Test
    fun `topological order respects dependencies`() {
        val graph = WorkflowGraph(
            nodes = listOf(node("a", NodeType.START), node("b", NodeType.TEXT), node("c", NodeType.TEXT)),
            edges = listOf(
                WorkflowEdge("e1", "a", toNodeId = "b"),
                WorkflowEdge("e2", "a", toNodeId = "c"),
            ),
        )
        val order = graph.topologicalOrder()
        assertNotNull(order)
        assertEquals(3, order!!.size)
        assertTrue(order.indexOf("a") < order.indexOf("b"))
        assertTrue(order.indexOf("a") < order.indexOf("c"))
    }

    @Test
    fun `cycle returns null order and invalid graph`() {
        val graph = WorkflowGraph(
            nodes = listOf(node("a", NodeType.TEXT), node("b", NodeType.TEXT)),
            edges = listOf(
                WorkflowEdge("e1", "a", toNodeId = "b"),
                WorkflowEdge("e2", "b", toNodeId = "a"),
            ),
        )
        assertNull(graph.topologicalOrder())
        assertTrue(graph.validate().any { it.contains("循环") })
    }

    @Test
    fun `validation reports dangling edges`() {
        val graph = WorkflowGraph(
            nodes = listOf(node("a", NodeType.TEXT)),
            edges = listOf(
                WorkflowEdge("e1", "a", toNodeId = "missing"),
                WorkflowEdge("e2", "nope", toNodeId = "a"),
            ),
        )
        val issues = graph.validate()
        assertEquals(2, issues.size)
    }

    @Test
    fun `multiple start nodes are invalid`() {
        val graph = WorkflowGraph(
            nodes = listOf(node("s1", NodeType.START), node("s2", NodeType.START)),
        )
        assertTrue(graph.validate().any { it.contains("开始") })
    }

    @Test
    fun `legacy steps convert to linear graph`() {
        val steps = listOf(
            WorkflowStep("a", "t1", StepType.TEXT, TextStepConfig("x")),
            WorkflowStep("b", "s", StepType.SHELL, ShellStepConfig("echo hi")),
        )
        val graph = legacyStepsToGraph(steps)
        assertEquals(4, graph.nodes.size)
        assertEquals(3, graph.edges.size)
        assertEquals(NodeType.START, graph.nodes.first().type)
        assertEquals(NodeType.END, graph.nodes.last().type)
        assertTrue(graph.validate().isEmpty())
    }

    @Test
    fun `graph round-trips through json`() {
        val graph = WorkflowGraph(
            nodes = listOf(
                node("start", NodeType.START),
                node("ai", NodeType.AI).copy(config = AiStepConfig("", "hello")),
            ),
            edges = listOf(WorkflowEdge("e1", "start", toNodeId = "ai")),
        )
        val json = JsonInstant.encodeToString(graph)
        val decoded = JsonInstant.decodeFromString<WorkflowGraph>(json)
        assertEquals(graph, decoded)
    }

    @Test
    fun `graph round-trips through json with new node types`() {
        val graph = WorkflowGraph(
            nodes = listOf(
                node("if", NodeType.IF).copy(config = IfStepConfig("{{node.a.output}} > 1")),
                node("for", NodeType.FOR).copy(config = ForStepConfig("[1,2]", "处理 {{item}}", "")),
                node("merge", NodeType.MERGE).copy(config = MergeStepConfig()),
                node("output", NodeType.OUTPUT).copy(config = OutputStepConfig("{{node.merge.output}}")),
                node("end", NodeType.END).copy(config = EndStepConfig()),
                node("start", NodeType.START).copy(config = StartStepConfig()),
            ),
        )
        val json = JsonInstant.encodeToString(graph)
        val decoded = JsonInstant.decodeFromString<WorkflowGraph>(json)
        assertEquals(graph, decoded)
    }

    @Test
    fun `effective graph falls back to legacy steps`() {
        val workflow = Workflow(
            id = "wf-1",
            name = "legacy",
            steps = listOf(WorkflowStep("a", "t", StepType.TEXT, TextStepConfig("x"))),
        )
        assertNull(workflow.graph)
        assertEquals(3, workflow.effectiveGraph.nodes.size)
        val withGraph = workflow.copy(graph = WorkflowGraph(nodes = listOf(node("n1", NodeType.TEXT))))
        assertEquals(1, withGraph.effectiveGraph.nodes.size)
    }
}

class ConditionEvaluatorTest {
    @Test
    fun `numeric comparison`() {
        val outputs = mapOf("a" to "1500")
        assertTrue(ConditionEvaluator.eval("{{node.a.output}} > 1000", outputs, emptyMap()))
        assertFalse(ConditionEvaluator.eval("{{node.a.output}} < 1000", outputs, emptyMap()))
        assertFalse(ConditionEvaluator.eval("{{node.a.output}} == 1000", outputs, emptyMap()))
    }

    @Test
    fun `string equality`() {
        assertTrue(
            ConditionEvaluator.eval(
                "{{input.mode}} == \"fast\"",
                emptyMap(),
                mapOf("mode" to "fast"),
            )
        )
        assertFalse(
            ConditionEvaluator.eval(
                "{{input.mode}} == \"fast\"",
                emptyMap(),
                mapOf("mode" to "slow"),
            )
        )
    }

    @Test
    fun `no operator means non-empty truthy`() {
        assertTrue(ConditionEvaluator.eval("{{node.a.output}}", mapOf("a" to "x"), emptyMap()))
        assertFalse(ConditionEvaluator.eval("{{node.a.output}}", mapOf("a" to ""), emptyMap()))
        assertFalse(ConditionEvaluator.eval("", emptyMap(), emptyMap()))
    }

    @Test
    fun `len operator`() {
        val outputs = mapOf("a" to "12345")
        assertTrue(ConditionEvaluator.eval("{{node.a.output|len}} > 3", outputs, emptyMap()))
    }
}

class NodeTemplateTest {
    @Test
    fun `node output reference is replaced`() {
        val rendered = TemplateRenderer.render(
            template = "结果：{{node.n1.output}}",
            nodeOutputs = mapOf("n1" to "ok"),
        )
        assertEquals("结果：ok", rendered)
    }

    @Test
    fun `node and step references resolve independently`() {
        val rendered = TemplateRenderer.render(
            template = "{{node.1.output}}|{{step.1.output}}",
            nodeOutputs = mapOf("1" to "node-value"),
            stepOutputs = mapOf(1 to "step-value"),
        )
        assertEquals("node-value|step-value", rendered)
    }

    @Test
    fun `missing node reference becomes empty string`() {
        val rendered = TemplateRenderer.render(
            template = "a{{node.missing.output}}b",
            nodeOutputs = emptyMap(),
        )
        assertEquals("ab", rendered)
    }
}

class EdgeConditionEvaluatorTest {
    private fun edge(condition: String?, fromPort: String = "out") =
        WorkflowEdge(id = "e", fromNodeId = "a", fromPort = fromPort, toNodeId = "b", condition = condition)

    private val sourceNode = WorkflowNode(id = "a", type = NodeType.TEXT, name = "A")

    @Test
    fun `null condition follows success and blocks failure`() {
        assertTrue(EdgeConditionEvaluator.shouldFollow(edge(null), sourceNode, NodeExecutionState.Success("x")))
        assertFalse(EdgeConditionEvaluator.shouldFollow(edge(null), sourceNode, NodeExecutionState.Failed("err")))
    }

    @Test
    fun `success condition follows only success`() {
        assertTrue(EdgeConditionEvaluator.shouldFollow(edge("success"), sourceNode, NodeExecutionState.Success("x")))
        assertTrue(EdgeConditionEvaluator.shouldFollow(edge("on_success"), sourceNode, NodeExecutionState.Success("x")))
        assertFalse(EdgeConditionEvaluator.shouldFollow(edge("success"), sourceNode, NodeExecutionState.Failed("err")))
    }

    @Test
    fun `error condition follows only failure`() {
        assertTrue(EdgeConditionEvaluator.shouldFollow(edge("error"), sourceNode, NodeExecutionState.Failed("boom")))
        assertTrue(EdgeConditionEvaluator.shouldFollow(edge("on_error"), sourceNode, NodeExecutionState.Failed("boom")))
        assertFalse(EdgeConditionEvaluator.shouldFollow(edge("error"), sourceNode, NodeExecutionState.Success("ok")))
    }

    @Test
    fun `true false conditions match boolean output`() {
        assertTrue(EdgeConditionEvaluator.shouldFollow(edge("true"), sourceNode, NodeExecutionState.Success("true")))
        assertTrue(EdgeConditionEvaluator.shouldFollow(edge("false"), sourceNode, NodeExecutionState.Success("0")))
        assertFalse(EdgeConditionEvaluator.shouldFollow(edge("true"), sourceNode, NodeExecutionState.Success("0")))
    }

    @Test
    fun `regex condition matches source output`() {
        assertTrue(EdgeConditionEvaluator.shouldFollow(edge("\\d+"), sourceNode, NodeExecutionState.Success("abc123")))
        assertFalse(EdgeConditionEvaluator.shouldFollow(edge("\\d+"), sourceNode, NodeExecutionState.Success("abc")))
    }

    @Test
    fun `fromPort true false fallback for if node`() {
        val ifNode = WorkflowNode(id = "a", type = NodeType.IF, name = "IF")
        assertTrue(EdgeConditionEvaluator.shouldFollow(edge(null, "true"), ifNode, NodeExecutionState.Success("true")))
        assertFalse(EdgeConditionEvaluator.shouldFollow(edge(null, "true"), ifNode, NodeExecutionState.Success("false")))
        assertTrue(EdgeConditionEvaluator.shouldFollow(edge(null, "false"), ifNode, NodeExecutionState.Success("false")))
    }

    @Test
    fun `hasErrorHandler detects error edge`() {
        assertTrue(EdgeConditionEvaluator.hasErrorHandler(sourceNode, listOf(edge("error"))))
        assertFalse(EdgeConditionEvaluator.hasErrorHandler(sourceNode, listOf(edge(null), edge("success"))))
    }
}

class WorkflowGraphV3Test {
    @Test
    fun `extract node round-trips through json`() {
        val graph = WorkflowGraph(
            nodes = listOf(
                WorkflowNode(id = "ex", type = NodeType.EXTRACT, name = "提取")
                    .copy(config = ExtractStepConfig(mode = ExtractMode.REGEX, source = "{{node.a.output}}", expression = "(\\d+)")),
            ),
        )
        val json = JsonInstant.encodeToString(graph)
        val decoded = JsonInstant.decodeFromString<WorkflowGraph>(json)
        assertEquals(graph, decoded)
        assertEquals(3, graph.version)
    }

    @Test
    fun `legacy edge json without condition decodes`() {
        val legacy = """{"version":2,"nodes":[],"edges":[{"id":"e1","fromNodeId":"a","fromPort":"out","toNodeId":"b","toPort":"in"}]}"""
        val graph = JsonInstant.decodeFromString<WorkflowGraph>(legacy)
        assertEquals(1, graph.edges.size)
        assertNull(graph.edges[0].condition)
    }

    @Test
    fun `edge with condition round-trips`() {
        val graph = WorkflowGraph(
            nodes = emptyList(),
            edges = listOf(WorkflowEdge(id = "e1", fromNodeId = "a", toNodeId = "b", condition = "error")),
        )
        val json = JsonInstant.encodeToString(graph)
        val decoded = JsonInstant.decodeFromString<WorkflowGraph>(json)
        assertEquals("error", decoded.edges[0].condition)
    }

    @Test
    fun `node reference ids extracted from configs`() {
        assertEquals(setOf("n1"), TextStepConfig("a {{node.n1.output}} b").nodeReferenceIds())
        assertEquals(setOf("n2"), AiStepConfig("", "p {{node.n2.output|len}}").nodeReferenceIds())
        assertEquals(setOf("n3"), ShellStepConfig("echo {{node.n3.output}}").nodeReferenceIds())
        assertEquals(setOf("n4"), IfStepConfig("{{node.n4.output}} == 1").nodeReferenceIds())
        assertEquals(setOf("n5"), ForStepConfig("{{node.n5.output}}", "x", "").nodeReferenceIds())
        assertEquals(
            setOf("n6", "n7"),
            ExtractStepConfig(source = "{{node.n6.output}}", expression = "{{node.n7.output}}").nodeReferenceIds(),
        )
        assertEquals(setOf("n8"), OutputStepConfig("{{node.n8.output}}").nodeReferenceIds())
    }

    @Test
    fun `withStats increments counters`() {
        val wf = Workflow(id = "w", name = "wf")
        val ok = wf.withStats(status = ExecutionStatus.SUCCESS, timestamp = 1000, success = true)
        assertEquals(1L, ok.totalExecutions)
        assertEquals(1L, ok.successfulExecutions)
        assertEquals(0L, ok.failedExecutions)
        assertEquals(ExecutionStatus.SUCCESS, ok.lastExecutionStatus)
        assertEquals(1000L, ok.lastExecutionTime)

        val fail = ok.withStats(status = ExecutionStatus.FAILED, timestamp = 2000, success = false)
        assertEquals(2L, fail.totalExecutions)
        assertEquals(1L, fail.successfulExecutions)
        assertEquals(1L, fail.failedExecutions)
        assertEquals(ExecutionStatus.FAILED, fail.lastExecutionStatus)
    }

    @Test
    fun `stats snapshot round-trips`() {
        val wf = Workflow(id = "w", name = "wf")
            .withStats(status = ExecutionStatus.SUCCESS, timestamp = 1000, success = true)
        val json = JsonInstant.encodeToString(wf.stats())
        val decoded = JsonInstant.decodeFromString<WorkflowStats>(json)
        assertEquals(1L, decoded.totalExecutions)
        assertEquals(ExecutionStatus.SUCCESS, decoded.lastExecutionStatus)
    }
}
