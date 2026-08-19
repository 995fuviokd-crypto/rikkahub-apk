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
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupMode
import me.rerere.rikkahub.data.model.GroupRun
import me.rerere.rikkahub.data.model.GroupToolRecord
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.data.repository.GroupRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceShellStatus
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
        reasoning: String = "",
        tools: String = "",
    )
}

/**
 * 成员单次调用的完整结果：正文 + 思考过程 + 工具调用记录。
 */
data class MemberCallResult(
    val text: String,
    val reasoning: String = "",
    val tools: List<GroupToolRecord> = emptyList(),
)

/**
 * 成员模型调用器：按 modelId 解析 Provider 并走完整生成管线
 * （流式输出、思考过程、工具调用循环、工作区执行）。
 */
interface GroupMemberCaller {
    suspend fun call(
        group: Group,
        member: GroupMember,
        prompt: String,
        onProgress: suspend (String) -> Unit = {},
    ): MemberCallResult

    suspend fun modelName(member: GroupMember): String = ""
}

/**
 * 基于 GenerationHandler 的真实成员调用器。
 *
 * 群组成员以临时 [Assistant]（携带成员 systemPrompt / 群组思考深度 / 工作区绑定）
 * 复用普通聊天的完整执行管线：reasoning 思考过程、tools 工具循环、workspace 工作区
 * 执行，并自动放行工具审批（群组无人工审批界面）。首 token 45 秒无响应即视为失败。
 */
class ProviderGroupMemberCaller(
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val localTools: LocalTools,
    private val workspaceRepository: WorkspaceRepository,
) : GroupMemberCaller {
    private companion object {
        const val TAG = "GroupMemberCaller"
        const val FIRST_TOKEN_TIMEOUT_MILLIS = 45_000L
        const val TOOL_OUTPUT_PREVIEW_LENGTH = 500
        val DEFAULT_GROUP_LOCAL_TOOLS = listOf(LocalToolOption.TimeInfo, LocalToolOption.DeviceInfo)
    }

    override suspend fun call(
        group: Group,
        member: GroupMember,
        prompt: String,
        onProgress: suspend (String) -> Unit,
    ): MemberCallResult {
        Log.i(TAG, "group member call start: role=${member.role} modelId=${member.modelId}")
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(member.modelId)
            ?: error("成员「${member.role}」的模型不存在，请重新配置")
        val providerSetting = model.findProvider(settings.providers)
            ?: error("成员「${member.role}」的模型未绑定可用的 Provider")
        Log.i(TAG, "group member call resolved: model=${model.modelId} provider=$providerSetting")

        val workspaceUuid = group.workspaceId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val assistant = Assistant(
            name = member.role,
            systemPrompt = member.systemPrompt ?: "",
            reasoningLevel = group.reasoningLevel,
            localTools = DEFAULT_GROUP_LOCAL_TOOLS,
            workspaceId = workspaceUuid,
            streamOutput = true,
        )

        val tools = buildList {
            addAll(localTools.getTools(assistant.localTools))
            if (group.enableTools && group.workspaceId != null) {
                val workspace = workspaceRepository.getById(group.workspaceId)
                if (workspace != null && workspace.shellStatus == WorkspaceShellStatus.READY.name) {
                    Log.i(TAG, "group member call: attach workspace tools for workspace=${group.workspaceId}")
                    addAll(createWorkspaceTools(group.workspaceId, workspaceRepository))
                }
            }
        }

        var snapshotText = ""
        var snapshotReasoning = ""
        var snapshotTools = emptyList<GroupToolRecord>()
        val notifiedToolIds = mutableSetOf<String>()
        val startTime = System.currentTimeMillis()

        val generation = generationHandler.generateText(
            settings = settings.copy(autoApproveTools = true),
            model = model,
            messages = listOf(UIMessage.user(prompt)),
            assistant = assistant,
            tools = tools,
            workspaceCwd = null,
        )

        val completed = try {
            withTimeoutOrNull(FIRST_TOKEN_TIMEOUT_MILLIS) {
                generation.collect { chunk ->
                    if (chunk is GenerationChunk.Messages) {
                        val last = chunk.messages.lastOrNull()
                        if (last != null) {
                            snapshotText = last.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString("\n") { it.text }
                            snapshotReasoning = last.parts.filterIsInstance<UIMessagePart.Reasoning>()
                                .joinToString("\n") { it.reasoning }
                            snapshotTools = last.parts.filterIsInstance<UIMessagePart.Tool>()
                                .map { tool ->
                                    GroupToolRecord(
                                        name = tool.toolName,
                                        input = tool.input,
                                        output = tool.output.filterIsInstance<UIMessagePart.Text>()
                                            .joinToString("\n") { it.text }
                                            .take(TOOL_OUTPUT_PREVIEW_LENGTH),
                                        isExecuted = tool.isExecuted,
                                    )
                                }
                            last.parts.filterIsInstance<UIMessagePart.Tool>()
                                .filter { it.isExecuted && notifiedToolIds.add(it.toolCallId) }
                                .forEach { onProgress("成员已调用工具「${it.toolName}」") }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "group member call failed: role=${member.role} ms=${System.currentTimeMillis() - startTime} err=${e.message}", e)
            throw e
        }

        if (completed == null) {
            error("成员「${member.role}」45 秒内无响应，请检查该成员的模型与网络配置")
        }
        Log.i(TAG, "group member call done: role=${member.role} ms=${System.currentTimeMillis() - startTime} len=${snapshotText.length} reasoning=${snapshotReasoning.length} tools=${snapshotTools.size}")
        return MemberCallResult(
            text = snapshotText,
            reasoning = snapshotReasoning,
            tools = snapshotTools,
        )
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

    /** 成员调用：失败/超时以异常形式传播，由 run() 统一标记失败。 */
    private suspend fun callOrNull(
        group: Group,
        member: GroupMember,
        prompt: String,
        onProgress: suspend (String) -> Unit = {},
    ): MemberCallResult = caller.call(group, member, prompt, onProgress)

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
        val planResult = callOrNull(group, orchestrator, planPrompt) { note ->
            onProgress(systemMessage(runId, group, note))
        }
        val planText = planResult.text
        onProgress(memberMessage(runId, group, orchestrator, planResult, MessageKind.PLAN))
        val subtasks = parseSubtasks(planText)
        if (subtasks.isEmpty()) error("编排器未生成有效的子任务计划")

        val outputs = mutableMapOf<String, String>()
        val executed = mutableSetOf<String>()

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
                        val result = callOrNull(group, member, prompt) { note ->
                            onProgress(systemMessage(runId, group, note))
                        }
                        onProgress(memberMessage(runId, group, member, result, MessageKind.SUBTASK))
                        task.id to result.text
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
            outputs.forEach { (id, result) ->
                val task = subtasks.find { it.id == id }
                appendLine("- 子任务「${task?.goal ?: id}」：${result.take(3000)}")
            }
            appendLine("请输出结构化的最终结论。")
        }
        val summaryResult = callOrNull(group, orchestrator, summaryPrompt) { note ->
            onProgress(systemMessage(runId, group, note))
        }
        onProgress(memberMessage(runId, group, orchestrator, summaryResult, MessageKind.SYSTEM))
        return summaryResult.text
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
            val result = callOrNull(group, member, prompt) { note ->
                onProgress(systemMessage(runId, group, note))
            }
            onProgress(memberMessage(runId, group, member, result, MessageKind.RESULT))
            current = result.text
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
                val result = callOrNull(group, member, prompt) { note ->
                    onProgress(systemMessage(runId, group, note))
                }
                onProgress(memberMessage(runId, group, member, result, MessageKind.REPLY))
                history += member to result.text
            }
        }

        val conclusionPrompt = buildString {
            appendLine("以下是群组围绕「$mission」的全部讨论内容，请综合各方观点，输出最终结论。")
            history.takeLast(MAX_CONTEXT_MESSAGES).forEach { (m, c) -> appendLine("- ${m.role}：${c.take(2000)}") }
            appendLine("请输出结构化的最终结论。")
        }
        val lead = group.members.firstOrNull() ?: error("群组没有成员")
        val conclusionResult = callOrNull(group, lead, conclusionPrompt) { note ->
            onProgress(systemMessage(runId, group, note))
        }
        onProgress(memberMessage(runId, group, lead, conclusionResult, MessageKind.SYSTEM))
        return conclusionResult.text
    }

    // ---------- 工具 ----------

    private suspend fun memberMessage(
        runId: String,
        group: Group,
        member: GroupMember,
        result: MemberCallResult,
        kind: MessageKind,
    ): GroupMessage {
        val modelName = runCatching { caller.modelName(member) }.getOrDefault("")
        val toolsJson = if (result.tools.isEmpty()) "" else JsonInstant.encodeToString(result.tools)
        val message = GroupMessage(
            id = Uuid.random().toString(),
            runId = runId,
            memberId = member.id,
            memberRole = member.role,
            memberModelName = modelName,
            content = result.text,
            kind = kind,
            reasoning = result.reasoning,
            tools = toolsJson,
            createdAt = System.currentTimeMillis(),
        )
        repository.addMessage(
            runId = runId,
            memberId = member.id,
            content = result.text,
            kind = kind,
            memberRole = member.role,
            memberModelName = modelName,
            reasoning = result.reasoning,
            tools = toolsJson,
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
        private const val MAX_CONTEXT_MESSAGES = 20
    }
}
