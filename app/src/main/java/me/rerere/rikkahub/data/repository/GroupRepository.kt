package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.GroupDAO
import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.db.entity.GroupMessageEntity
import me.rerere.rikkahub.data.db.entity.GroupRunEntity
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupMode
import me.rerere.rikkahub.data.model.GroupRun
import me.rerere.rikkahub.data.model.GroupSummary
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.data.ai.group.GroupStore
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class GroupRepository(
    private val dao: GroupDAO,
) : GroupStore {
    fun listGroups(): Flow<List<Group>> = dao.listGroups().map { list -> list.map { it.toGroup() } }

    /**
     * 会话列表群组分区数据源：每个群组的最新运行状态 + 最新消息预览，实时更新。
     */
    fun groupSummaries(): Flow<List<GroupSummary>> = dao.listGroups().flatMapLatest { groups ->
        if (groups.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(groups.map { entity -> entity.toSummaryFlow() }) { summaries ->
                summaries.toList()
            }
        }
    }

    private fun GroupEntity.toSummaryFlow(): Flow<GroupSummary> =
        dao.latestRun(id).flatMapLatest { run ->
            if (run == null) {
                flowOf(
                    GroupSummary(
                        id = id,
                        name = name,
                        updatedAt = updatedAt,
                    )
                )
            } else {
                dao.latestMessage(run.id).map { message ->
                    GroupSummary(
                        id = id,
                        name = name,
                        latestMessage = message?.content,
                        status = runCatching { RunStatus.valueOf(run.status) }.getOrNull(),
                        updatedAt = run.createdAt,
                    )
                }
            }
        }

    fun getGroup(id: String): Flow<Group?> = dao.getGroupFlow(id).map { it?.toGroup() }

    suspend fun getGroupById(id: String): Group? = dao.getGroup(id)?.toGroup()

    suspend fun save(group: Group): Group {
        val now = System.currentTimeMillis()
        val entity = GroupEntity(
            id = group.id,
            name = group.name.trim().ifBlank { "未命名群组" },
            mode = group.mode.name,
            membersJson = JsonInstant.encodeToString(group.members),
            orchestratorId = group.orchestratorId,
            createdAt = if (group.createdAt > 0) group.createdAt else now,
            updatedAt = now,
        )
        dao.upsertGroup(entity)
        return entity.toGroup()
    }

    suspend fun create(
        name: String,
        members: List<GroupMember>,
        orchestratorId: String? = null,
    ): Group {
        return save(
            Group(
                id = Uuid.random().toString(),
                name = name,
                members = members,
                orchestratorId = orchestratorId,
            )
        )
    }

    suspend fun delete(id: String): Boolean {
        val existed = dao.getGroup(id) != null
        dao.deleteGroupCascade(id)
        return existed
    }

    fun listRuns(groupId: String): Flow<List<GroupRun>> =
        dao.listRuns(groupId).map { list -> list.map { it.toRun() } }

    fun getRun(id: String): Flow<GroupRun?> = dao.getRunFlow(id).map { it?.toRun() }

    override suspend fun getRunById(id: String): GroupRun? = dao.getRun(id)?.toRun()

    override suspend fun upsertRun(run: GroupRun) {
        dao.upsertRun(
            GroupRunEntity(
                id = run.id,
                groupId = run.groupId,
                mission = run.mission,
                status = run.status.name,
                summary = run.summary,
                createdAt = run.createdAt,
                startedAt = run.startedAt,
                endedAt = run.endedAt,
            )
        )
    }

    fun listMessages(runId: String): Flow<List<GroupMessage>> =
        dao.listMessages(runId).map { list -> list.map { it.toMessage() } }

    override suspend fun getMessages(runId: String): List<GroupMessage> =
        dao.getMessages(runId).map { it.toMessage() }

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
        dao.upsertMessage(
            GroupMessageEntity(
                id = Uuid.random().toString(),
                runId = runId,
                memberId = memberId,
                memberRole = memberRole,
                memberModelName = memberModelName,
                content = content,
                kind = kind.name,
                reasoning = reasoning,
                tools = tools,
                createdAt = System.currentTimeMillis(),
            )
        )
    }
}

private fun GroupEntity.toGroup(): Group {
    val members = runCatching {
        JsonInstant.decodeFromString<List<GroupMember>>(membersJson)
    }.getOrDefault(emptyList())
    return Group(
        id = id,
        name = name,
        mode = runCatching { GroupMode.valueOf(mode) }.getOrDefault(GroupMode.ORCHESTRATOR_WORKER),
        members = members,
        orchestratorId = orchestratorId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun GroupRunEntity.toRun(): GroupRun {
    return GroupRun(
        id = id,
        groupId = groupId,
        mission = mission,
        status = runCatching { RunStatus.valueOf(status) }.getOrDefault(RunStatus.FAILED),
        summary = summary,
        createdAt = createdAt,
        startedAt = startedAt,
        endedAt = endedAt,
    )
}

private fun GroupMessageEntity.toMessage(): GroupMessage {
    return GroupMessage(
        id = id,
        runId = runId,
        memberId = memberId,
        memberRole = memberRole,
        memberModelName = memberModelName,
        content = content,
        kind = runCatching { MessageKind.valueOf(kind) }.getOrDefault(MessageKind.REPLY),
        reasoning = reasoning,
        tools = tools,
        createdAt = createdAt,
    )
}
