package me.rerere.rikkahub.ui.pages.extensions.group

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.rikkahub.data.ai.group.GroupMemberCaller
import me.rerere.rikkahub.data.ai.group.GroupRunner
import me.rerere.rikkahub.data.db.dao.GroupDAO
import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.db.entity.GroupMessageEntity
import me.rerere.rikkahub.data.db.entity.GroupRunEntity
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupMode
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.data.repository.GroupRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

private class FakeGroupDAO : GroupDAO {
    private val groups = mutableMapOf<String, GroupEntity>()
    private val groupsState = MutableStateFlow<Map<String, GroupEntity>>(emptyMap())
    private val runs = mutableMapOf<String, GroupRunEntity>()
    private val runsState = MutableStateFlow<Map<String, GroupRunEntity>>(emptyMap())
    private val messages = mutableMapOf<String, GroupMessageEntity>()
    private val messagesState = MutableStateFlow<Map<String, GroupMessageEntity>>(emptyMap())

    override fun listGroups(): Flow<List<GroupEntity>> =
        groupsState.map { m -> m.values.sortedByDescending { it.updatedAt } }

    override suspend fun getGroup(id: String): GroupEntity? = groups[id]

    override fun getGroupFlow(id: String): Flow<GroupEntity?> =
        groupsState.map { it[id] }

    override suspend fun upsertGroup(group: GroupEntity) {
        groups[group.id] = group
        groupsState.value = groups.toMap()
    }

    override suspend fun deleteGroupById(id: String): Int {
        val removed = groups.remove(id)
        groupsState.value = groups.toMap()
        return if (removed != null) 1 else 0
    }

    override fun listRuns(groupId: String): Flow<List<GroupRunEntity>> =
        runsState.map { m -> m.values.filter { it.groupId == groupId }.sortedByDescending { it.createdAt } }

    override fun latestRun(groupId: String): Flow<GroupRunEntity?> =
        runsState.map { m -> m.values.filter { it.groupId == groupId }.maxByOrNull { it.createdAt } }

    override fun latestMessage(runId: String): Flow<GroupMessageEntity?> =
        messagesState.map { m -> m.values.filter { it.runId == runId }.maxByOrNull { it.createdAt } }

    override suspend fun getRun(id: String): GroupRunEntity? = runs[id]

    override fun getRunFlow(id: String): Flow<GroupRunEntity?> =
        runsState.map { it[id] }

    override suspend fun upsertRun(run: GroupRunEntity) {
        runs[run.id] = run
        runsState.value = runs.toMap()
    }

    override suspend fun deleteRunsByGroup(groupId: String) {
        runs.keys.filter { runs[it]?.groupId == groupId }.forEach { runs.remove(it) }
        runsState.value = runs.toMap()
    }

    override fun listMessages(runId: String): Flow<List<GroupMessageEntity>> =
        messagesState.map { m -> m.values.filter { it.runId == runId }.sortedBy { it.createdAt } }

    override suspend fun getMessages(runId: String): List<GroupMessageEntity> =
        messages.values.filter { it.runId == runId }.sortedBy { it.createdAt }

    override suspend fun upsertMessage(message: GroupMessageEntity) {
        messages[message.id] = message
        messagesState.value = messages.toMap()
    }

    override suspend fun deleteMessagesByRun(runId: String) {
        messages.keys.filter { messages[it]?.runId == runId }.forEach { messages.remove(it) }
        messagesState.value = messages.toMap()
    }

    override suspend fun deleteMessagesByGroup(groupId: String) {
        messages.keys.filter {
            runs[messages[it]?.runId]?.groupId == groupId
        }.forEach { messages.remove(it) }
        messagesState.value = messages.toMap()
    }
}

private class FakeCaller : GroupMemberCaller {
    override suspend fun call(
        group: Group,
        member: GroupMember,
        prompt: String,
        onProgress: suspend (String) -> Unit,
    ): me.rerere.rikkahub.data.ai.group.MemberCallResult =
        me.rerere.rikkahub.data.ai.group.MemberCallResult(text = "${member.role} 的回复")

    override suspend fun modelName(member: GroupMember): String = "model-${member.role}"
}

@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailVMTest {
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `launchRun persists run and messages and auto-selects the new run`() =
        runTest(scheduler) {
            val dao = FakeGroupDAO()
            val repository = GroupRepository(dao)
            repository.save(
                Group(
                    id = "g1",
                    name = "测试群组",
                    mode = GroupMode.DEBATE,
                    members = listOf(GroupMember(id = "m1", modelId = Uuid.random(), role = "A")),
                    debateRounds = 1,
                )
            )
            val runner = GroupRunner(FakeCaller(), repository)
            val vm = GroupDetailVM("g1", repository, runner)

            val groupJob = launch(UnconfinedTestDispatcher(testScheduler)) { vm.group.collect {} }
            val collected = mutableListOf<List<GroupMessage>>()
            val messageJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.messages.collect { collected += it }
            }
            advanceUntilIdle()

            assertNotNull(vm.group.value)

            vm.launchRun("帮我讨论一个方案")
            advanceUntilIdle()

            val runs = vm.runs.value
            assertEquals(1, runs.size)
            assertEquals(RunStatus.SUCCESS, runs.first().status)
            assertNotNull(vm.selectedRunId.value)
            assertEquals(runs.first().id, vm.selectedRunId.value)

            assertTrue(collected.isNotEmpty())
            val latest = collected.last()
            assertTrue(latest.isNotEmpty())
            assertTrue(latest.any { it.kind == MessageKind.SYSTEM })
            assertTrue(latest.any { it.kind == MessageKind.REPLY })
            assertTrue(latest.all { it.runId == runs.first().id })

            groupJob.cancel()
            messageJob.cancel()
        }

    @Test
    fun `selectRun switches messages to the chosen run`() =
        runTest(scheduler) {
            val dao = FakeGroupDAO()
            val repository = GroupRepository(dao)
            repository.save(
                Group(
                    id = "g1",
                    name = "测试群组",
                    mode = GroupMode.PIPELINE,
                    members = listOf(GroupMember(id = "m1", modelId = Uuid.random(), role = "A")),
                )
            )
            repository.upsertRun(
                me.rerere.rikkahub.data.model.GroupRun(
                    id = "run-1",
                    groupId = "g1",
                    mission = "第一次任务",
                    status = RunStatus.SUCCESS,
                    createdAt = 1000,
                    startedAt = 1000,
                    endedAt = 2000,
                    summary = "ok",
                )
            )
            repository.addMessage("run-1", "m1", "第一条消息", MessageKind.REPLY, "A", "model-A", "", "")
            repository.upsertRun(
                me.rerere.rikkahub.data.model.GroupRun(
                    id = "run-2",
                    groupId = "g1",
                    mission = "第二次任务",
                    status = RunStatus.SUCCESS,
                    createdAt = 2000,
                    startedAt = 2000,
                    endedAt = 3000,
                    summary = "ok",
                )
            )
            repository.addMessage("run-2", "m1", "第二条消息", MessageKind.REPLY, "A", "model-A", "", "")

            val vm = GroupDetailVM("g1", repository, GroupRunner(FakeCaller(), repository))
            val collected = mutableListOf<List<GroupMessage>>()
            val messageJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.messages.collect { collected += it }
            }
            advanceUntilIdle()

            vm.selectRun("run-1")
            advanceUntilIdle()
            assertEquals(listOf("第一条消息"), vm.messages.value.map { it.content })

            vm.selectRun("run-2")
            advanceUntilIdle()
            assertEquals(listOf("第二条消息"), vm.messages.value.map { it.content })

            assertTrue(collected.last().all { it.runId == "run-2" })

            messageJob.cancel()
        }
}
