package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.WorkflowDAO
import me.rerere.rikkahub.data.db.dao.WorkflowExecutionRecordDAO
import me.rerere.rikkahub.data.db.entity.WorkflowEntity
import me.rerere.rikkahub.data.db.entity.WorkflowExecutionRecordEntity
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowExecutionRecord
import me.rerere.rikkahub.data.model.WorkflowExecutionFailureStage
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowRunLogEntry
import me.rerere.rikkahub.data.model.WorkflowStats
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.rikkahub.data.model.legacyStepsToGraph
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class WorkflowRepository(
    private val dao: WorkflowDAO,
    private val executionRecordDao: WorkflowExecutionRecordDAO,
) {
    fun listFlow(): Flow<List<WorkflowEntity>> = dao.listFlow()

    fun listFlows(): Flow<List<Workflow>> = dao.listFlow().map { list -> list.map { it.toWorkflow() } }

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
            statsJson = JsonInstant.encodeToString(workflow.stats()),
            createdAt = if (workflow.createdAt > 0) workflow.createdAt else now,
            updatedAt = now,
        )
        dao.upsert(entity)
        return entity.toWorkflow()
    }

    /**
     * 仅更新执行统计（不改变图结构、不刷新 updatedAt）。
     */
    suspend fun updateStats(workflow: Workflow): Workflow {
        val entity = dao.getById(workflow.id) ?: return save(workflow)
        val updated = entity.copy(statsJson = JsonInstant.encodeToString(workflow.stats()))
        dao.upsert(updated)
        return updated.toWorkflow()
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
        executionRecordDao.deleteByWorkflow(id)
        return dao.deleteById(id) > 0
    }

    fun executionRecordsFlow(workflowId: String): Flow<List<WorkflowExecutionRecord>> {
        return executionRecordDao.listFlow(workflowId).map { list ->
            list.map { it.toRecord() }
        }
    }

    suspend fun saveExecutionRecord(record: WorkflowExecutionRecord) {
        executionRecordDao.upsert(record.toEntity())
    }

    suspend fun getLatestExecutionRecord(workflowId: String): WorkflowExecutionRecord? {
        return executionRecordDao.getLatest(workflowId)?.toRecord()
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
    val stats = runCatching {
        val json = statsJson.trim()
        if (json.isEmpty() || json == "{}" || json == "null") null
        else JsonInstant.decodeFromString<WorkflowStats>(json)
    }.getOrNull()
    return Workflow(
        id = id,
        name = name,
        description = description,
        steps = steps,
        graph = graph,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastExecutionTime = stats?.lastExecutionTime ?: 0,
        lastExecutionStatus = stats?.lastExecutionStatus,
        totalExecutions = stats?.totalExecutions ?: 0,
        successfulExecutions = stats?.successfulExecutions ?: 0,
        failedExecutions = stats?.failedExecutions ?: 0,
    )
}

private fun WorkflowExecutionRecord.toEntity(): WorkflowExecutionRecordEntity {
    return WorkflowExecutionRecordEntity(
        runId = runId,
        workflowId = workflowId,
        workflowName = workflowName,
        startedAt = startedAt,
        finishedAt = finishedAt,
        success = success,
        message = message,
        logsJson = JsonInstant.encodeToString(logs),
        failureStage = failureStage?.name,
        failureReason = failureReason,
    )
}

private fun WorkflowExecutionRecordEntity.toRecord(): WorkflowExecutionRecord {
    return WorkflowExecutionRecord(
        runId = runId,
        workflowId = workflowId,
        workflowName = workflowName,
        startedAt = startedAt,
        finishedAt = finishedAt,
        success = success,
        message = message,
        logs = runCatching {
            JsonInstant.decodeFromString<List<WorkflowRunLogEntry>>(logsJson)
        }.getOrDefault(emptyList()),
        failureStage = failureStage?.let { name ->
            runCatching { WorkflowExecutionFailureStage.valueOf(name) }.getOrNull()
        },
        failureReason = failureReason,
    )
}
