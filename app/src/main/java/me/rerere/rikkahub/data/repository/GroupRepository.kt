package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
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
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class GroupRepository(
    private val dao: GroupDAO,
) {
    fun listGroups(): Flow<List<Group>> = dao.listGroups().map { list -> list.map { it.toGroup() } }

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
            debateRounds = group.debateRounds,
            createdAt = if (group.createdAt > 0) group.createdAt else now,
            updatedAt = now,
        )
        dao.upsertGroup(entity)
        return entity.toGroup()
    }

    suspend fun create(
        name: String,
        mode: GroupMode,
        members: List<GroupMember>,
        orchestratorId: String? = null,
        debateRounds: Int = 3,
    ): Group {
        return save(
            Group(
                id = Uuid.random().toString(),
                name = name,
                mode = mode,
                members = members,
                orchestratorId = orchestratorId,
                debateRounds = debateRounds,
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

    suspend fun getRunById(id: String): GroupRun? = dao.getRun(id)?.toRun()

    suspend fun upsertRun(run: GroupRun) {
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

    suspend fun getMessages(runId: String): List<GroupMessage> =
        dao.getMessages(runId).map { it.toMessage() }

    suspend fun addMessage(
        runId: String,
        memberId: String,
        content: String,
        kind: MessageKind,
        memberRole: String = "",
        memberModelName: String = "",
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
        mode = runCatching { GroupMode.valueOf(mode) }.getOrDefault(GroupMode.DEBATE),
        members = members,
        orchestratorId = orchestratorId,
        debateRounds = debateRounds,
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
        createdAt = createdAt,
    )
}
