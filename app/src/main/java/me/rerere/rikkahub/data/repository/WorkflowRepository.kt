package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.WorkflowDAO
import me.rerere.rikkahub.data.db.entity.WorkflowEntity
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class WorkflowRepository(
    private val dao: WorkflowDAO,
) {
    fun listFlow(): Flow<List<WorkflowEntity>> = dao.listFlow()

    fun getFlow(id: String): Flow<Workflow?> = dao.getFlow(id).map { it?.toWorkflow() }

    suspend fun getById(id: String): WorkflowEntity? = dao.getById(id)

    suspend fun loadWorkflow(id: String): Workflow? {
        val entity = dao.getById(id) ?: return null
        return entity.toWorkflow()
    }

    suspend fun loadAll(): List<Workflow> = dao.getAll().map { it.toWorkflow() }

    suspend fun save(workflow: Workflow): Workflow {
        val now = System.currentTimeMillis()
        val entity = WorkflowEntity(
            id = workflow.id,
            name = workflow.name.trim().ifBlank { "未命名工作流" },
            description = workflow.description,
            stepsJson = JsonInstant.encodeToString(workflow.steps),
            createdAt = if (workflow.createdAt > 0) workflow.createdAt else now,
            updatedAt = now,
        )
        dao.upsert(entity)
        return entity.toWorkflow()
    }

    suspend fun create(name: String, steps: List<WorkflowStep> = emptyList()): Workflow {
        val id = Uuid.random().toString()
        return save(
            Workflow(
                id = id,
                name = name,
                steps = steps,
            )
        )
    }

    suspend fun delete(id: String): Boolean {
        return dao.deleteById(id) > 0
    }
}

private fun WorkflowEntity.toWorkflow(): Workflow {
    val steps = runCatching {
        JsonInstant.decodeFromString<List<WorkflowStep>>(stepsJson)
    }.getOrDefault(emptyList())
    return Workflow(
        id = id,
        name = name,
        description = description,
        steps = steps,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
