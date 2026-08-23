package me.rerere.rikkahub.data.ai.workflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.ExtractMode
import me.rerere.rikkahub.data.model.ExtractStepConfig
import me.rerere.rikkahub.data.model.NodeType
import me.rerere.rikkahub.data.model.OutputStepConfig
import me.rerere.rikkahub.data.model.ShellStepConfig
import me.rerere.rikkahub.data.model.StartStepConfig
import me.rerere.rikkahub.data.model.TextStepConfig
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowEdge
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowNode
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowRunnerTest {

    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun runner(): WorkflowRunner {
        val context = RuntimeEnvironment.getApplication()
        return WorkflowRunner(
            providerManager = ProviderManager(me.rerere.ai.provider.ProviderHttpClient(OkHttpClient()), context),
            settingsStore = SettingsStore(context, AppScope()),
            httpClient = OkHttpClient(),
        )
    }

    private fun graph(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>) =
        WorkflowGraph(nodes = nodes, edges = edges)

    private fun text(id: String, content: String) =
        WorkflowNode(id = id, type = NodeType.TEXT, name = id, config = TextStepConfig(content))

    @Test
    fun `error edge recovers failed node and workflow succeeds`() = runTest {
        val workflow = Workflow(
            id = "wf",
            name = "错误处理",
            graph = graph(
                nodes = listOf(
                    WorkflowNode(id = "s", type = NodeType.START, name = "开始", config = StartStepConfig()),
                    WorkflowNode(id = "fail", type = NodeType.SHELL, name = "失败节点", config = ShellStepConfig(command = "false")),
                    WorkflowNode(id = "recover", type = NodeType.TEXT, name = "补救", config = TextStepConfig("已补救")),
                ),
                edges = listOf(
                    WorkflowEdge(id = "e1", fromNodeId = "s", toNodeId = "fail"),
                    WorkflowEdge(id = "e2", fromNodeId = "fail", fromPort = "out", toNodeId = "recover", condition = "error"),
                ),
            ),
        )
        val result = runner().run(workflow)

        assertTrue("错误边已处理失败，工作流应成功", result.succeeded)
        val recover = result.nodes.last { it.nodeId == "recover" }
        assertEquals(StepStatus.SUCCESS, recover.status)
        assertEquals("已补救", recover.output)
    }

    @Test
    fun `unhandled failure marks workflow as failed but other nodes still run`() = runTest {
        val workflow = Workflow(
            id = "wf",
            name = "未处理失败",
            graph = graph(
                nodes = listOf(
                    WorkflowNode(id = "s", type = NodeType.START, name = "开始", config = StartStepConfig()),
                    WorkflowNode(id = "fail", type = NodeType.SHELL, name = "失败节点", config = ShellStepConfig(command = "false")),
                    WorkflowNode(id = "independent", type = NodeType.TEXT, name = "独立节点", config = TextStepConfig("照常运行")),
                ),
                edges = listOf(
                    WorkflowEdge(id = "e1", fromNodeId = "s", toNodeId = "fail"),
                    WorkflowEdge(id = "e2", fromNodeId = "s", toNodeId = "independent"),
                ),
            ),
        )
        val result = runner().run(workflow)

        assertFalse("存在未被错误边处理的失败节点，工作流应失败", result.succeeded)
        val independent = result.nodes.last { it.nodeId == "independent" }
        assertEquals(StepStatus.SUCCESS, independent.status)
    }

    @Test
    fun `extract node extracts regex group`() = runTest {
        val workflow = Workflow(
            id = "wf",
            name = "提取",
            graph = graph(
                nodes = listOf(
                    WorkflowNode(id = "s", type = NodeType.START, name = "开始", config = StartStepConfig()),
                    text("a", "订单号 123，金额 45"),
                    WorkflowNode(
                        id = "ex",
                        type = NodeType.EXTRACT,
                        name = "提取数字",
                        config = ExtractStepConfig(
                            mode = ExtractMode.REGEX,
                            source = "{{node.a.output}}",
                            expression = "订单号 (\\d+)",
                            group = 1,
                        ),
                    ),
                    WorkflowNode(id = "out", type = NodeType.OUTPUT, name = "输出", config = OutputStepConfig("{{node.ex.output}}")),
                ),
                edges = listOf(
                    WorkflowEdge(id = "e1", fromNodeId = "s", toNodeId = "a"),
                    WorkflowEdge(id = "e2", fromNodeId = "a", toNodeId = "ex"),
                    WorkflowEdge(id = "e3", fromNodeId = "ex", toNodeId = "out"),
                ),
            ),
        )
        val result = runner().run(workflow)

        assertTrue(result.succeeded)
        val extract = result.nodes.last { it.nodeId == "ex" }
        assertEquals("123", extract.output)
    }

    @Test
    fun `implicit dependency via node reference orders execution`() = runTest {
        val workflow = Workflow(
            id = "wf",
            name = "隐式依赖",
            graph = graph(
                nodes = listOf(
                    WorkflowNode(id = "s", type = NodeType.START, name = "开始", config = StartStepConfig()),
                    text("a", "hello"),
                    text("b", "{{node.a.output}}!"),
                ),
                edges = listOf(
                    WorkflowEdge(id = "e1", fromNodeId = "s", toNodeId = "a"),
                ),
            ),
        )
        val result = runner().run(workflow)

        assertTrue(result.succeeded)
        val nodeB = result.nodes.last { it.nodeId == "b" }
        assertEquals("hello!", nodeB.output)
    }

    @Test
    fun `failed node records attempt count with retries`() = runTest {
        val workflow = Workflow(
            id = "wf",
            name = "重试",
            graph = graph(
                nodes = listOf(
                    WorkflowNode(id = "s", type = NodeType.START, name = "开始", config = StartStepConfig()),
                    WorkflowNode(id = "fail", type = NodeType.SHELL, name = "失败节点", config = ShellStepConfig(command = "false")),
                ),
                edges = listOf(
                    WorkflowEdge(id = "e1", fromNodeId = "s", toNodeId = "fail"),
                ),
            ),
        )
        val result = runner().run(workflow, retries = 2, retryDelayMillis = 0)

        assertFalse(result.succeeded)
        val runningRuns = result.nodes.filter { it.nodeId == "fail" && it.status == StepStatus.RUNNING }
        assertEquals(3, runningRuns.size) // 初始 + 2 次重试
        val failedRuns = result.nodes.filter { it.nodeId == "fail" && it.status == StepStatus.FAILED }
        assertEquals(1, failedRuns.size)
        assertEquals(2, failedRuns.last().attempt)
    }
}
