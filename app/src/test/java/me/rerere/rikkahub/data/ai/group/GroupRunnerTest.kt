package me.rerere.rikkahub.data.ai.group

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupMode
import me.rerere.rikkahub.data.model.GroupRun
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.data.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

private class FakeStore : GroupStore {
    val runs = mutableMapOf<String, GroupRun>()
    val messages = mutableListOf<GroupMessage>()

    override suspend fun upsertRun(run: GroupRun) {
        runs[run.id] = run
    }

    override suspend fun getRunById(id: String): GroupRun? = runs[id]

    override suspend fun getMessages(runId: String): List<GroupMessage> =
        messages.filter { it.runId == runId }

    override suspend fun addMessage(
        runId: String,
        memberId: String,
        content: String,
        kind: MessageKind,
        memberRole: String,
        memberModelName: String,
        reasoning: String,
        tools: String,
    ) {
        messages += GroupMessage(
            id = Uuid.random().toString(),
            runId = runId,
            memberId = memberId,
            memberRole = memberRole,
            memberModelName = memberModelName,
            content = content,
            kind = kind,
            reasoning = reasoning,
            tools = tools,
            createdAt = System.currentTimeMillis(),
        )
    }
}

private class FakeCaller(
    private val handler: suspend (GroupMember, String) -> String,
) : GroupMemberCaller {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun call(
        group: Group,
        member: GroupMember,
        prompt: String,
        onProgress: suspend (String) -> Unit,
    ): MemberCallResult {
        calls += member.role to prompt
        return MemberCallResult(text = handler(member, prompt))
    }

    override suspend fun modelName(member: GroupMember): String = "model-${member.role}"
}

private fun member(id: String, role: String): GroupMember =
    GroupMember(id = id, modelId = Uuid.random(), role = role)

class GroupRunnerTest {

    @Test
    fun `orchestrator worker mode respects dependency order`() = runBlocking {
        val orchestrator = member("o", "主编")
        val worker1 = member("w1", "调研员")
        val worker2 = member("w2", "撰稿员")
        val group = Group(
            id = "g1",
            name = "g",
            mode = GroupMode.ORCHESTRATOR_WORKER,
            members = listOf(orchestrator, worker1, worker2),
            orchestratorId = "o",
        )

        val caller = FakeCaller { m, prompt ->
            when {
                prompt.contains("JSON 数组") -> """[{"id":"t1","goal":"调研","memberId":"w1"},{"id":"t2","goal":"写作","memberId":"w2","dependsOn":["t1"]}]"""
                prompt.contains("汇总") -> "总结完成"
                m.role == "调研员" -> "调研结果A"
                else -> "文章B"
            }
        }
        val store = FakeStore()
        val runner = GroupRunner(caller, store)

        val result = runner.run(group, "完成报告")

        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals("总结完成", result.summary)

        val roles = caller.calls.map { it.first }
        assertEquals(listOf("主编", "调研员", "撰稿员", "主编"), roles)

        val kinds = store.messages.map { it.kind }
        assertEquals(
            listOf(MessageKind.SYSTEM, MessageKind.PLAN, MessageKind.SUBTASK, MessageKind.SUBTASK, MessageKind.SYSTEM),
            kinds,
        )
    }

    @Test
    fun `orchestrator plan with code fence is parsed`() = runBlocking {
        val orchestrator = member("o", "主编")
        val worker1 = member("w1", "工作者")
        val group = Group(
            id = "g1",
            name = "g",
            mode = GroupMode.ORCHESTRATOR_WORKER,
            members = listOf(orchestrator, worker1),
            orchestratorId = "o",
        )
        val caller = FakeCaller { m, prompt ->
            when {
                prompt.contains("JSON 数组") -> "```json\n[{\"id\":\"t1\",\"goal\":\"调研\",\"memberId\":\"w1\"}]\n```"
                prompt.contains("汇总") -> "总结完成"
                else -> "结果"
            }
        }
        val store = FakeStore()
        val runner = GroupRunner(caller, store)

        val result = runner.run(group, "任务")

        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(listOf("主编", "工作者", "主编"), caller.calls.map { it.first })
    }

    @Test
    fun `pipeline mode passes output between members`() = runBlocking {
        val a = member("a", "A")
        val b = member("b", "B")
        val group = Group(
            id = "g1",
            name = "g",
            mode = GroupMode.PIPELINE,
            members = listOf(a, b),
        )
        val caller = FakeCaller { m, prompt ->
            when (m.role) {
                "A" -> "A的产出"
                else -> prompt
            }
        }
        val store = FakeStore()
        val runner = GroupRunner(caller, store)

        val result = runner.run(group, "指令")

        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(listOf("A", "B"), caller.calls.map { it.first })
        assertTrue(caller.calls[1].second.contains("A的产出"))
        assertTrue(result.summary.contains("A的产出"))
        assertEquals(
            listOf(MessageKind.SYSTEM, MessageKind.RESULT, MessageKind.RESULT),
            store.messages.map { it.kind },
        )
    }

    @Test
    fun `debate mode runs all members each round then conclusion`() = runBlocking {
        val a = member("a", "A")
        val b = member("b", "B")
        val group = Group(
            id = "g1",
            name = "g",
            mode = GroupMode.DEBATE,
            members = listOf(a, b),
            debateRounds = 2,
        )
        val caller = FakeCaller { m, prompt ->
            when {
                prompt.contains("最终结论") -> "结论"
                else -> "${m.role}发言"
            }
        }
        val store = FakeStore()
        val runner = GroupRunner(caller, store)

        val result = runner.run(group, "主题")

        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals("结论", result.summary)
        assertEquals(listOf("A", "B", "A", "B", "A"), caller.calls.map { it.first })
        assertEquals(
            listOf(MessageKind.SYSTEM, MessageKind.REPLY, MessageKind.REPLY, MessageKind.REPLY, MessageKind.REPLY, MessageKind.SYSTEM),
            store.messages.map { it.kind },
        )
    }

    @Test
    fun `member failure marks run failed and keeps partial results`() = runBlocking {
        val orchestrator = member("o", "主编")
        val worker1 = member("w1", "工作者")
        val group = Group(
            id = "g1",
            name = "g",
            mode = GroupMode.ORCHESTRATOR_WORKER,
            members = listOf(orchestrator, worker1),
            orchestratorId = "o",
        )
        val caller = FakeCaller { m, prompt ->
            when {
                prompt.contains("JSON 数组") -> """[{"id":"t1","goal":"调研","memberId":"w1"}]"""
                prompt.contains("汇总") -> error("汇总失败")
                else -> "结果"
            }
        }
        val store = FakeStore()
        val runner = GroupRunner(caller, store)

        val result = runner.run(group, "任务")

        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.summary.contains("汇总失败"))
        assertTrue(store.messages.last().kind == MessageKind.SYSTEM)
        assertTrue(store.messages.last().content.contains("运行失败"))
    }

    @Test
    fun `external runId is reused in store`() = runBlocking {
        val a = member("a", "A")
        val group = Group(
            id = "g1",
            name = "g",
            mode = GroupMode.DEBATE,
            members = listOf(a),
            debateRounds = 1,
        )
        val caller = FakeCaller { m, prompt -> "发言" }
        val store = FakeStore()
        val runner = GroupRunner(caller, store)

        val result = runner.run(group, "任务", runId = "custom-run")

        assertEquals("custom-run", result.id)
        assertNotNull(store.getRunById("custom-run"))
        assertTrue(store.messages.all { it.runId == "custom-run" })
    }

    @Test
    fun `appended instruction during run is injected into subsequent member prompts`() = runBlocking {
        val a = member("a", "A")
        val b = member("b", "B")
        val group = Group(
            id = "g1",
            name = "g",
            mode = GroupMode.PIPELINE,
            members = listOf(a, b),
        )
        val store = FakeStore()
        val caller = FakeCaller { m, prompt ->
            when (m.role) {
                "A" -> {
                    store.addMessage(
                        runId = "run-append",
                        memberId = GroupRunner.USER_MEMBER_ID,
                        content = "请改用中文输出",
                        kind = MessageKind.USER,
                        memberRole = "用户",
                    )
                    "A 的产出"
                }
                else -> prompt
            }
        }
        val runner = GroupRunner(caller, store)

        val result = runner.run(group, "任务", runId = "run-append")

        assertEquals(RunStatus.SUCCESS, result.status)
        val prompts = caller.calls.map { it.second }
        assertTrue("B 的 prompt 应包含追加指令，实际：${prompts[1]}", prompts[1].contains("请改用中文输出"))
    }

    @Test
    fun `appended instruction injected into every debate round`() = runBlocking {
        val a = member("a", "A")
        val b = member("b", "B")
        val group = Group(
            id = "g1",
            name = "g",
            mode = GroupMode.DEBATE,
            members = listOf(a, b),
            debateRounds = 2,
        )
        val store = FakeStore()
        store.addMessage(
            runId = "run-debate",
            memberId = GroupRunner.USER_MEMBER_ID,
            content = "补充：需要列出风险",
            kind = MessageKind.USER,
            memberRole = "用户",
            reasoning = "",
            tools = "",
        )
        val caller = FakeCaller { m, prompt ->
            when {
                prompt.contains("最终结论") -> "结论"
                else -> "${m.role}发言"
            }
        }
        val runner = GroupRunner(caller, store)

        val result = runner.run(group, "主题", runId = "run-debate")

        assertEquals(RunStatus.SUCCESS, result.status)
        val prompts = caller.calls.map { it.second }
        assertTrue("所有成员调用都应注入补充指令", prompts.all { it.contains("补充：需要列出风险") })
    }

    @Test
    fun `cancelling run marks it stopped not success`() = runBlocking {
        val a = member("a", "A")
        val group = Group(
            id = "g1",
            name = "g",
            mode = GroupMode.DEBATE,
            members = listOf(a),
            debateRounds = 10,
        )
        val store = FakeStore()
        val caller = FakeCaller { m, prompt ->
            delay(10)
            "发言"
        }
        val runner = GroupRunner(caller, store)

        val job = launch {
            runner.run(group, "任务", runId = "run-stop")
        }
        delay(5)
        job.cancel()

        val run = store.getRunById("run-stop")
        assertNotNull(run)
        assertEquals(RunStatus.RUNNING, run?.status)
    }

    @Test
    fun `cancellation propagates for caller to mark stopped`() = runBlocking {
        val a = member("a", "A")
        val group = Group(
            id = "g1",
            name = "g",
            mode = GroupMode.DEBATE,
            members = listOf(a),
            debateRounds = 10,
        )
        val store = FakeStore()
        val caller = FakeCaller { m, prompt ->
            delay(10)
            "发言"
        }
        val runner = GroupRunner(caller, store)
        val runId = "run-stop-prop"
        val job = launch {
            try {
                runner.run(group, "任务", runId = runId)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val run = store.getRunById(runId)
                    if (run != null && run.status == RunStatus.RUNNING) {
                        store.upsertRun(run.copy(status = RunStatus.STOPPED, endedAt = System.currentTimeMillis()))
                    }
                }
                throw e
            }
        }
        delay(5)
        job.cancel()
        job.join()

        assertEquals(RunStatus.STOPPED, store.getRunById(runId)?.status)
    }
}
