package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.WorkflowDAO
import me.rerere.rikkahub.data.db.entity.WorkflowEntity
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.rikkahub.data.model.legacyStepsToGraph
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
            stepsJson = "[]",
            graphJson = JsonInstant.encodeToString(workflow.effectiveGraph),
            createdAt = if (workflow.createdAt > 0) workflow.createdAt else now,
            updatedAt = now,
        )
        dao.upsert(entity)
        return entity.toWorkflow()
    }

    suspend fun create(
        name: String,
        steps: List<WorkflowStep> = emptyList(),
        graph: WorkflowGraph? = null,
        description: String = "",
    ): Workflow {
        val id = Uuid.random().toString()
        return save(
            Workflow(
                id = id,
                name = name,
                description = description,
                steps = steps,
                graph = graph,
            )
        )
    }

    suspend fun delete(id: String): Boolean {
        return dao.deleteById(id) > 0
    }
}

private fun WorkflowEntity.toWorkflow(): Workflow {
    val graph = runCatching {
        val json = graphJson.trim()
        if (json.isEmpty() || json == "{}" || json == "null") null
        else JsonInstant.decodeFromString<WorkflowGraph>(json)
    }.getOrNull()
    val steps = if (graph != null) {
        emptyList()
    } else {
        runCatching {
            JsonInstant.decodeFromString<List<WorkflowStep>>(stepsJson)
        }.getOrDefault(emptyList())
    }
    return Workflow(
        id = id,
        name = name,
        description = description,
        steps = steps,
        graph = graph,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
