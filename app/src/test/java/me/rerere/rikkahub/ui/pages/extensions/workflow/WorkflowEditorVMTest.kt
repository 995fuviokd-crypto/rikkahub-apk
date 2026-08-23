package me.rerere.rikkahub.ui.pages.extensions.workflow

import io.pebbletemplates.pebble.PebbleEngine
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.workflow.WorkflowRunResult
import me.rerere.rikkahub.data.ai.workflow.WorkflowRunner
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkflowDAO
import me.rerere.rikkahub.data.db.dao.WorkflowExecutionRecordDAO
import me.rerere.rikkahub.data.db.entity.WorkflowEntity
import me.rerere.rikkahub.data.db.entity.WorkflowExecutionRecordEntity
import me.rerere.rikkahub.data.model.NodeType
import me.rerere.rikkahub.data.model.TextStepConfig
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowEdge
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowNode
import me.rerere.rikkahub.data.repository.WorkflowRepository
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeWorkflowDAO : WorkflowDAO {
    private val store = mutableMapOf<String, WorkflowEntity>()
    private val state = MutableStateFlow<Map<String, WorkflowEntity>>(emptyMap())

    override fun listFlow(): Flow<List<WorkflowEntity>> =
        flowOf(store.values.sortedByDescending { it.updatedAt })

    override suspend fun getById(id: String): WorkflowEntity? = store[id]

    override fun getFlow(id: String): Flow<WorkflowEntity?> =
        flowOf(store[id])

    override suspend fun getAll(): List<WorkflowEntity> = store.values.toList()

    override suspend fun upsert(workflow: WorkflowEntity) {
        store[workflow.id] = workflow
        state.value = store.toMap()
    }

    override suspend fun deleteById(id: String): Int {
        val removed = store.remove(id)
        state.value = store.toMap()
        return if (removed != null) 1 else 0
    }
}

private class FakeWorkflowExecutionRecordDAO : WorkflowExecutionRecordDAO {
    private val store = mutableMapOf<String, WorkflowExecutionRecordEntity>()

    override fun listFlow(workflowId: String): Flow<List<WorkflowExecutionRecordEntity>> =
        flowOf(store.values.filter { it.workflowId == workflowId }.sortedByDescending { it.startedAt })

    override suspend fun getLatest(workflowId: String): WorkflowExecutionRecordEntity? =
        store.values.filter { it.workflowId == workflowId }.maxByOrNull { it.startedAt }

    override suspend fun upsert(record: WorkflowExecutionRecordEntity) {
        store[record.runId] = record
    }

    override suspend fun deleteByWorkflow(workflowId: String): Int {
        val keys = store.filterValues { it.workflowId == workflowId }.keys
        keys.forEach { store.remove(it) }
        return keys.size
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WorkflowEditorVMTest {
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        GlobalContext.stopKoin()
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(module { single { PebbleEngine.Builder().build() } })
        }
    }

    @After
    fun tearDown() {
        GlobalContext.stopKoin()
        Dispatchers.resetMain()
    }

    private fun runner(): WorkflowRunner {
        val context = RuntimeEnvironment.getApplication()
        return StubRunner(
            providerManager = ProviderManager(me.rerere.ai.provider.ProviderHttpClient(OkHttpClient()), context),
            settingsStore = SettingsStore(context, AppScope()),
            httpClient = OkHttpClient(),
        )
    }

    private suspend fun newVM(dao: FakeWorkflowDAO, repository: WorkflowRepository): WorkflowEditorVM =
        WorkflowEditorVM(
            id = "wf1",
            repository = repository,
            runner = runner(),
        )

    private suspend fun seed(dao: FakeWorkflowDAO): WorkflowRepository {
        val repository = WorkflowRepository(dao, FakeWorkflowExecutionRecordDAO())
        repository.save(
            Workflow(
                id = "wf1",
                name = "测试流程",
                graph = WorkflowGraph(
                    nodes = listOf(
                        WorkflowNode(id = "a", type = NodeType.TEXT, name = "A", config = TextStepConfig(), x = 0f, y = 0f),
                        WorkflowNode(id = "b", type = NodeType.TEXT, name = "B", config = TextStepConfig(), x = 300f, y = 0f),
                        WorkflowNode(id = "c", type = NodeType.TEXT, name = "C", config = TextStepConfig(), x = 0f, y = 300f),
                    ),
                    edges = listOf(WorkflowEdge(id = "e1", fromNodeId = "a", toNodeId = "b")),
                ),
            ),
        )
        return repository
    }


    @Test
    fun `tap node selects it, tap another switches selection`() = runTest(scheduler) {
        val dao = FakeWorkflowDAO()
        val repository = seed(dao)
        val vm = newVM(dao, repository)
        advanceUntilIdle()

        vm.tapNode("a")
        assertEquals(setOf("a"), vm.selectedNodeIds.value)

        vm.tapNode("b")
        assertEquals(setOf("b"), vm.selectedNodeIds.value)
    }

    @Test
    fun `select nodes via box selects multiple`() = runTest(scheduler) {
        val dao = FakeWorkflowDAO()
        val repository = seed(dao)
        val vm = newVM(dao, repository)
        advanceUntilIdle()

        vm.selectNodes(setOf("a", "c"))
        assertEquals(setOf("a", "c"), vm.selectedNodeIds.value)
    }

    @Test
    fun `move selected nodes applies offset to all`() = runTest(scheduler) {
        val dao = FakeWorkflowDAO()
        val repository = seed(dao)
        val vm = newVM(dao, repository)
        advanceUntilIdle()

        vm.selectNodes(setOf("a", "c"))
        vm.moveSelectedNodes(50f, 40f)
        advanceUntilIdle()

        val nodes = vm.workflow.value!!.effectiveGraph.nodes
        assertEquals(50f, nodes.first { it.id == "a" }.x)
        assertEquals(40f, nodes.first { it.id == "a" }.y)
        assertEquals(50f, nodes.first { it.id == "c" }.x)
        assertEquals(340f, nodes.first { it.id == "c" }.y)
        assertEquals(300f, nodes.first { it.id == "b" }.x)
    }

    @Test
    fun `remove selected nodes removes them and connected edges`() = runTest(scheduler) {
        val dao = FakeWorkflowDAO()
        val repository = seed(dao)
        val vm = newVM(dao, repository)
        advanceUntilIdle()

        vm.selectNodes(setOf("a"))
        vm.removeSelectedNodes()
        advanceUntilIdle()

        val graph = vm.workflow.value!!.effectiveGraph
        assertFalse(graph.nodes.any { it.id == "a" })
        assertFalse(graph.edges.any { it.fromNodeId == "a" || it.toNodeId == "a" })
        assertTrue(graph.nodes.any { it.id == "b" })
        assertEquals(emptySet<String>(), vm.selectedNodeIds.value)
    }

    @Test
    fun `undo restores previous graph and redo reapplies`() = runTest(scheduler) {
        val dao = FakeWorkflowDAO()
        val repository = seed(dao)
        val vm = newVM(dao, repository)
        advanceUntilIdle()

        vm.addNode(NodeType.TEXT, 100f, 100f)
        advanceUntilIdle()
        val withNode = vm.workflow.value!!.effectiveGraph
        assertEquals(4, withNode.nodes.size)

        vm.undo()
        advanceUntilIdle()
        val reverted = vm.workflow.value!!.effectiveGraph
        assertEquals(3, reverted.nodes.size)

        vm.redo()
        advanceUntilIdle()
        val reapplied = vm.workflow.value!!.effectiveGraph
        assertEquals(4, reapplied.nodes.size)
    }

    @Test
    fun `new edit clears redo stack`() = runTest(scheduler) {
        val dao = FakeWorkflowDAO()
        val repository = seed(dao)
        val vm = newVM(dao, repository)
        advanceUntilIdle()

        vm.addNode(NodeType.TEXT, 100f, 100f)
        advanceUntilIdle()
        vm.undo()
        advanceUntilIdle()

        vm.moveNode("a", 500f, 500f)
        advanceUntilIdle()

        assertFalse(vm.canRedo.value)
    }

    @Test
    fun `auto layout arranges nodes by topological layer`() = runTest(scheduler) {
        val dao = FakeWorkflowDAO()
        val repository = WorkflowRepository(dao, FakeWorkflowExecutionRecordDAO())
        repository.save(
            Workflow(
                id = "wf1",
                name = "布局",
                graph = WorkflowGraph(
                    nodes = listOf(
                        WorkflowNode(id = "s", type = NodeType.START, name = "开始", x = 0f, y = 0f),
                        WorkflowNode(id = "ai", type = NodeType.TEXT, name = "AI", x = 0f, y = 0f),
                        WorkflowNode(id = "end", type = NodeType.TEXT, name = "结束", x = 0f, y = 0f),
                    ),
                    edges = listOf(
                        WorkflowEdge(id = "e1", fromNodeId = "s", toNodeId = "ai"),
                        WorkflowEdge(id = "e2", fromNodeId = "ai", toNodeId = "end"),
                    ),
                ),
            ),
        )
        val vm = WorkflowEditorVM("wf1", repository, runner())
        advanceUntilIdle()

        vm.autoLayout()
        advanceUntilIdle()

        val nodes = vm.workflow.value!!.effectiveGraph.nodes
        val s = nodes.first { it.id == "s" }
        val ai = nodes.first { it.id == "ai" }
        val end = nodes.first { it.id == "end" }
        assertTrue("开始应在 AI 左侧", s.x < ai.x)
        assertTrue("AI 应在结束左侧", ai.x < end.x)
    }

    @Test
    fun `auto layout is rejected on cyclic graph`() = runTest(scheduler) {
        val dao = FakeWorkflowDAO()
        val repository = WorkflowRepository(dao, FakeWorkflowExecutionRecordDAO())
        repository.save(
            Workflow(
                id = "wf1",
                name = "环形",
                graph = WorkflowGraph(
                    nodes = listOf(
                        WorkflowNode(id = "a", type = NodeType.TEXT, name = "A", x = 0f, y = 0f),
                        WorkflowNode(id = "b", type = NodeType.TEXT, name = "B", x = 0f, y = 0f),
                    ),
                    edges = listOf(
                        WorkflowEdge(id = "e1", fromNodeId = "a", toNodeId = "b"),
                        WorkflowEdge(id = "e2", fromNodeId = "b", toNodeId = "a"),
                    ),
                ),
            ),
        )
        val vm = WorkflowEditorVM("wf1", repository, runner())
        advanceUntilIdle()

        vm.autoLayout()
        advanceUntilIdle()

        // 环存在：autoLayout 应拒绝，坐标保持不变
        val nodes = vm.workflow.value!!.effectiveGraph.nodes
        assertEquals(0f, nodes.first { it.id == "a" }.x)
        assertEquals(0f, nodes.first { it.id == "b" }.x)
    }
}

private class StubRunner(
    providerManager: ProviderManager,
    settingsStore: SettingsStore,
    httpClient: OkHttpClient,
) : WorkflowRunner(providerManager, settingsStore, httpClient) {
    override suspend fun run(
        workflow: Workflow,
        input: Map<String, String>,
        retries: Int,
        retryDelayMillis: Long,
        onProgress: (me.rerere.rikkahub.data.ai.workflow.RunProgress) -> Unit,
    ): WorkflowRunResult = WorkflowRunResult(workflow.id, emptyList(), true)
}
