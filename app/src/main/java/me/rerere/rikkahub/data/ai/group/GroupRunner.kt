package me.rerere.rikkahub.data.ai.group

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupMode
import me.rerere.rikkahub.data.model.GroupRun
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.data.repository.GroupRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * 群组消息持久化接口：GroupRunner 只依赖该接口，便于测试注入内存实现。
 */
interface GroupStore {
    suspend fun upsertRun(run: GroupRun)

    suspend fun getRunById(id: String): GroupRun?

    suspend fun addMessage(
        runId: String,
        memberId: String,
        content: String,
        kind: MessageKind,
        memberRole: String = "",
        memberModelName: String = "",
    )
}

/**
 * 成员模型调用器：统一按 modelId 解析 Provider 并生成文本。
 */
interface GroupMemberCaller {
    suspend fun call(member: GroupMember, prompt: String): String

    suspend fun modelName(member: GroupMember): String = ""
}

/**
 * 基于 ProviderManager + SettingsStore 的真实成员调用器。
 */
class ProviderGroupMemberCaller(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
) : GroupMemberCaller {
    private companion object {
        const val TAG = "GroupMemberCaller"
    }
    override suspend fun call(member: GroupMember, prompt: String): String {
        Log.i(TAG, "group member call start: role=${member.role} modelId=${member.modelId}")
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(member.modelId)
            ?: error("成员「${member.role}」的模型不存在，请重新配置")
        val providerSetting = model.findProvider(settings.providers)
            ?: error("成员「${member.role}」的模型未绑定可用的 Provider")
        Log.i(TAG, "group member call resolved: model=${model.modelId} provider=$providerSetting")
        val provider = providerManager.getProviderByType(providerSetting)
        val params = TextGenerationParams(model = model)
        val text = StringBuilder()
        var chunkCount = 0
        val startTime = System.currentTimeMillis()
        try {
            provider.streamText(
                providerSetting = providerSetting,
                messages = listOf(UIMessage.user(prompt)),
                params = params,
            ).collect { chunk ->
                if (chunk is StreamChunk.TextDelta) {
                    chunkCount++
                    text.append(chunk.text)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "group member call failed: role=${member.role} ms=${System.currentTimeMillis() - startTime} chunks=$chunkCount err=${e.message}", e)
            throw e
        }
        Log.i(TAG, "group member call done: role=${member.role} ms=${System.currentTimeMillis() - startTime} chunks=$chunkCount len=${text.length}")
        return text.toString()
    }

    override suspend fun modelName(member: GroupMember): String =
        runCatching {
            settingsStore.settingsFlow.value.findModelById(member.modelId)?.displayName ?: ""
        }.getOrDefault("")
}

/**
 * 群组协作执行引擎。
 * 三种模式：
 * - ORCHESTRATOR_WORKER：编排器拆解子任务并分派给工作者，最后汇总；
 * - PIPELINE：成员按顺序接力，上一位输出作为下一位输入；
 * - DEBATE：按轮次全体成员轮流发言，最后生成结论。
 *
 * 所有消息实时写入 GroupRepository 并通过 onProgress 回调推送。
 */
class GroupRunner(
    private val caller: GroupMemberCaller,
    private val repository: GroupStore,
) {
    private val json = JsonInstant

    suspend fun run(
        group: Group,
        mission: String,
        runId: String? = null,
        onProgress: (GroupMessage) -> Unit = {},
    ): GroupRun {
        val now = System.currentTimeMillis()
        val run = GroupRun(
            id = runId ?: Uuid.random().toString(),
            groupId = group.id,
            mission = mission,
            status = RunStatus.RUNNING,
            createdAt = now,
            startedAt = now,
        )
        repository.upsertRun(run)
        onProgress(systemMessage(run.id, group, "群组任务已发布：$mission"))

        val summary = try {
            when (group.mode) {
                GroupMode.ORCHESTRATOR_WORKER -> runOrchestratorWorker(group, run.id, mission, onProgress)
                GroupMode.PIPELINE -> runPipeline(group, run.id, mission, onProgress)
                GroupMode.DEBATE -> runDebate(group, run.id, mission, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onProgress(systemMessage(run.id, group, "运行失败：${e.message}"))
            val failed = run.copy(status = RunStatus.FAILED, summary = e.message.orEmpty(), endedAt = System.currentTimeMillis())
            repository.upsertRun(failed)
            return failed
        }

        val finished = run.copy(status = RunStatus.SUCCESS, summary = summary, endedAt = System.currentTimeMillis())
        repository.upsertRun(finished)
        return finished
    }

    /** 带超时的成员调用，返回 null 表示超时。 */
    private suspend fun callOrNull(member: GroupMember, prompt: String): String? =
        withTimeoutOrNull(SINGLE_CALL_TIMEOUT_MILLIS) { caller.call(member, prompt) }

    // ---------- 编排器-工作者 ----------

    private suspend fun runOrchestratorWorker(
        group: Group,
        runId: String,
        mission: String,
        onProgress: (GroupMessage) -> Unit,
    ): String {
        val orchestrator = group.orchestrator
            ?: error("编排器模式必须指定一个主编排器成员")
        val workers = group.members.filter { it.id != orchestrator.id }
        if (workers.isEmpty()) error("编排器模式至少需要一个工作者成员")

        val planPrompt = buildString {
            appendLine("你是群组的主编排器「${orchestrator.role}」，负责把任务拆解并分派给工作者。")
            appendLine("可用成员：")
            group.members.forEach { m ->
                appendLine("- id=${m.id}，角色=${m.role}${m.systemPrompt?.let { "，职责：$it" } ?: ""}")
            }
            appendLine("任务指令：$mission")
            appendLine("请输出一个 JSON 数组（不要输出其他内容），每个元素为 {\"id\":\"t1\",\"goal\":\"...\",\"memberId\":\"成员id\",\"dependsOn\":[\"t1\"]}，dependsOn 为该子任务依赖的前置子任务 id 列表，无依赖可省略。")
        }
        val planText = callOrNull(orchestrator, planPrompt)
            ?: error("编排器生成任务计划超时（45 秒无响应），请检查成员的模型与网络配置")
        onProgress(memberMessage(runId, group, orchestrator, planText, MessageKind.PLAN))
        val subtasks = parseSubtasks(planText)
        if (subtasks.isEmpty()) error("编排器未生成有效的子任务计划")

        val outputs = mutableMapOf<String, String>()
        val executed = mutableSetOf<String>()
        var workerFailed = false

        while (subtasks.any { it.id !in executed }) {
            if (!coroutineContext.isActive) return "（已停止）"
            val ready = subtasks.filter { t -> t.id !in executed && t.dependsOn.all { it in executed } }
            if (ready.isEmpty()) {
                subtasks.filter { it.id !in executed }.forEach { t ->
                    onProgress(systemMessage(runId, group, "子任务「${t.goal}」依赖无法满足，已跳过"))
                }
                break
            }
            val results = coroutineScope {
                ready.map { task ->
                    async {
                        val member = group.members.find { it.id == task.memberId } ?: orchestrator
                        val ctx = task.dependsOn.mapNotNull { outputs[it] }
                        val prompt = buildString {
                            appendLine("你是群组成员「${member.role}」，请完成分配给你的子任务。")
                            member.systemPrompt?.let { appendLine("你的职责：$it") }
                            if (ctx.isNotEmpty()) {
                                appendLine("前置子任务结果：")
                                ctx.forEach { appendLine(it.take(2500)) }
                            }
                            appendLine("子任务目标：${task.goal}")
                            appendLine("整体任务指令：$mission")
                            appendLine("请直接输出结果，不要解释过程。")
                        }
                        val result = callOrNull(member, prompt)
                        if (result == null) {
                            workerFailed = true
                            onProgress(systemMessage(runId, group, "成员「${member.role}」执行子任务超时（45 秒无响应），已跳过该子任务"))
                            task.id to "【失败】执行超时"
                        } else {
                            onProgress(memberMessage(runId, group, member, result, MessageKind.SUBTASK))
                            task.id to result
                        }
                    }
                }.awaitAll()
            }
            results.forEach { (id, result) ->
                outputs[id] = result
                executed += id
            }
        }

        val summaryPrompt = buildString {
            appendLine("你是主编排器「${orchestrator.role}」，请汇总群组针对任务的执行结果，输出最终结论。")
            appendLine("任务指令：$mission")
            if (workerFailed) appendLine("注意：部分成员执行失败或超时，请如实说明。")
            outputs.forEach { (id, result) ->
                val task = subtasks.find { it.id == id }
                appendLine("- 子任务「${task?.goal ?: id}」：${result.take(3000)}")
            }
            appendLine("请输出结构化的最终结论。")
        }
        val summary = callOrNull(orchestrator, summaryPrompt) ?: "（汇总超时，以上子任务结果即为最终成果）"
        onProgress(memberMessage(runId, group, orchestrator, summary, MessageKind.SYSTEM))
        return summary
    }

    // ---------- 流水线 ----------

    private suspend fun runPipeline(
        group: Group,
        runId: String,
        mission: String,
        onProgress: (GroupMessage) -> Unit,
    ): String {
        var current = mission
        group.members.forEachIndexed { index, member ->
            if (!coroutineContext.isActive) return "（已停止）"
            val prompt = buildString {
                appendLine("你是群组成员「${member.role}」，参与流水线协作，任务指令：$mission")
                member.systemPrompt?.let { appendLine("你的职责：$it") }
                if (index > 0) {
                    appendLine("前一位成员输出：${current.take(4000)}")
                    appendLine("请基于以上输出完成你的环节，直接输出结果。")
                } else {
                    appendLine("你是第一个环节，请直接处理任务并输出结果。")
                }
            }
            val result = callOrNull(member, prompt)
            if (result == null) {
                error("成员「${member.role}」执行超时（45 秒无响应），请检查该成员的模型与网络配置")
            }
            onProgress(memberMessage(runId, group, member, result, MessageKind.RESULT))
            current = result
        }
        return current
    }

    // ---------- 自由讨论 ----------

    private suspend fun runDebate(
        group: Group,
        runId: String,
        mission: String,
        onProgress: (GroupMessage) -> Unit,
    ): String {
        val rounds = group.debateRounds.coerceAtLeast(1)
        val history = mutableListOf<Pair<GroupMember, String>>()

        for (round in 1..rounds) {
            if (!coroutineContext.isActive) return "（已停止）"
            for (member in group.members) {
                if (!coroutineContext.isActive) return "（已停止）"
                val previous = history.takeLast(MAX_CONTEXT_MESSAGES)
                val prompt = buildString {
                    appendLine("你是群组成员「${member.role}」，参与第 $round 轮讨论。")
                    member.systemPrompt?.let { appendLine("你的职责：$it") }
                    appendLine("讨论主题：$mission")
                    if (previous.isNotEmpty()) {
                        appendLine("已有发言：")
                        previous.forEach { (m, c) -> appendLine("- ${m.role}：${c.take(1500)}") }
                    }
                    appendLine("请给出你的观点、分析或补充，直接输出内容。")
                }
                val result = callOrNull(member, prompt)
                if (result == null) {
                    onProgress(systemMessage(runId, group, "成员「${member.role}」第 $round 轮发言超时（45 秒无响应），已跳过"))
                    continue
                }
                onProgress(memberMessage(runId, group, member, result, MessageKind.REPLY))
                history += member to result
            }
        }

        val conclusionPrompt = buildString {
            appendLine("以下是群组围绕「$mission」的全部讨论内容，请综合各方观点，输出最终结论。")
            history.takeLast(MAX_CONTEXT_MESSAGES).forEach { (m, c) -> appendLine("- ${m.role}：${c.take(2000)}") }
            appendLine("请输出结构化的最终结论。")
        }
        val lead = group.members.firstOrNull() ?: error("群组没有成员")
        val conclusion = callOrNull(lead, conclusionPrompt) ?: "（生成结论超时，以上讨论即为成果）"
        onProgress(memberMessage(runId, group, lead, conclusion, MessageKind.SYSTEM))
        return conclusion
    }

    // ---------- 工具 ----------

    private suspend fun memberMessage(
        runId: String,
        group: Group,
        member: GroupMember,
        content: String,
        kind: MessageKind,
    ): GroupMessage {
        val modelName = runCatching { caller.modelName(member) }.getOrDefault("")
        val message = GroupMessage(
            id = Uuid.random().toString(),
            runId = runId,
            memberId = member.id,
            memberRole = member.role,
            memberModelName = modelName,
            content = content,
            kind = kind,
            createdAt = System.currentTimeMillis(),
        )
        repository.addMessage(
            runId = runId,
            memberId = member.id,
            content = content,
            kind = kind,
            memberRole = member.role,
            memberModelName = modelName,
        )
        return message
    }

    private suspend fun systemMessage(runId: String, group: Group, content: String): GroupMessage {
        val message = GroupMessage(
            id = Uuid.random().toString(),
            runId = runId,
            memberId = SYSTEM_MEMBER_ID,
            memberRole = "系统",
            memberModelName = "",
            content = content,
            kind = MessageKind.SYSTEM,
            createdAt = System.currentTimeMillis(),
        )
        repository.addMessage(
            runId = runId,
            memberId = SYSTEM_MEMBER_ID,
            content = content,
            kind = MessageKind.SYSTEM,
            memberRole = "系统",
        )
        return message
    }

    private fun parseSubtasks(planText: String): List<Subtask> {
        val raw = planText.trim()
        val candidates = listOf(raw, raw.substringAfter("[").substringBeforeLast("]"))
        for (candidate in candidates) {
            val wrapped = if (candidate.startsWith("[")) candidate else "[$candidate]"
            runCatching {
                json.decodeFromString<List<Subtask>>(wrapped)
            }.getOrNull()?.let { return it }
        }
        return emptyList()
    }

    @Serializable
    private data class Subtask(
        val id: String,
        val goal: String,
        val memberId: String,
        val dependsOn: List<String> = emptyList(),
    )

    companion object {
        const val SYSTEM_MEMBER_ID = "__system__"
        private const val SINGLE_CALL_TIMEOUT_MILLIS = 45_000L
        private const val MAX_CONTEXT_MESSAGES = 20
    }
}
