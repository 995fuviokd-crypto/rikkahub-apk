package me.rerere.rikkahub.data.ai.group

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.GroupDAO
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupRun
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.data.repository.GroupRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

private class RoomTestCaller : GroupMemberCaller {
    override suspend fun call(
        group: Group,
        member: GroupMember,
        prompt: String,
        onProgress: suspend (String) -> Unit,
    ): MemberCallResult {
        val text = when {
            prompt.contains("JSON 数组") -> """[{"id":"t1","goal":"调研","memberId":"m1"}]"""
            prompt.contains("汇总") -> "总结完成"
            else -> "${member.role} 的回复"
        }
        return MemberCallResult(text = text)
    }

    override suspend fun modelName(member: GroupMember): String = "model-${member.role}"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GroupRoomPersistenceTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: GroupDAO

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.groupDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `runner persists run and messages into real room`() = runBlocking {
        val repo = GroupRepository(dao)
        val group = repo.save(
            Group(
                id = "g1",
                name = "测试群组",
                members = listOf(
                    GroupMember(id = "o", modelId = Uuid.random(), role = "主编"),
                    GroupMember(id = "m1", modelId = Uuid.random(), role = "A"),
                ),
                orchestratorId = "o",
            )
        )
        val runner = GroupRunner(RoomTestCaller(), repo)

        val result = runner.run(group, "调研任务")

        assertEquals(RunStatus.SUCCESS, result.status)
        val stored = dao.getMessages(result.id)
        assertTrue(stored.isNotEmpty())
        assertTrue(stored.any { it.memberId == "m1" })
        assertTrue(stored.any { it.memberId == GroupRunner.SYSTEM_MEMBER_ID })
    }

    @Test
    fun `listMessages flow re-emits after insert`() = runBlocking {
        val repo = GroupRepository(dao)
        repo.save(Group(id = "g1", name = "g"))
        repo.upsertRun(GroupRun(id = "run-1", groupId = "g1", mission = "m"))

        val emissions = mutableListOf<List<GroupMessage>>()
        val job = launch { repo.listMessages("run-1").collect { emissions += it } }
        waitUntil { emissions.isNotEmpty() }
        assertTrue(emissions.last().isEmpty())

        repo.addMessage("run-1", "m1", "第一条消息", MessageKind.REPLY, "A", "model-A", "", "")
        waitUntil { emissions.isNotEmpty() && emissions.last().any { it.content == "第一条消息" } }

        job.cancel()
    }

    @Test
    fun `run flow updates status after upsert`() = runBlocking {
        val repo = GroupRepository(dao)
        repo.save(Group(id = "g1", name = "g"))
        repo.upsertRun(GroupRun(id = "run-1", groupId = "g1", mission = "m", status = RunStatus.RUNNING))

        val emissions = mutableListOf<GroupRun?>()
        val job = launch { repo.getRun("run-1").collect { emissions += it } }
        waitUntil { emissions.isNotEmpty() }
        assertEquals(RunStatus.RUNNING, emissions.last()?.status)

        repo.upsertRun(
            repo.getRunById("run-1")!!.copy(
                status = RunStatus.SUCCESS,
                summary = "ok",
                endedAt = System.currentTimeMillis(),
            )
        )
        waitUntil { emissions.size >= 2 && emissions.last()?.status == RunStatus.SUCCESS }

        job.cancel()
    }

    private suspend fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        assertTrue("condition not met within $timeoutMs ms", condition())
    }
}
