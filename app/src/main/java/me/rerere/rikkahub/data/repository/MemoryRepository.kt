package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.memory.MemoryFtsManager
import me.rerere.rikkahub.data.memory.MemoryOps
import me.rerere.rikkahub.data.memory.MemoryRecallService
import me.rerere.rikkahub.data.memory.MemoryScope
import me.rerere.rikkahub.data.memory.RecallItem
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryTarget

/**
 * 记忆仓库：统一提供旧的列表 API（兼容现有 UI）与新的 scope/recall API。
 * 内部基于 scope-recall 记忆引擎（MemoryOps + MemoryRecallService）。
 */
class MemoryRepository(private val memoryDAO: MemoryDAO, private val database: AppDatabase) {

    private val memoryOps = MemoryOps(database)
    private val recallService = MemoryRecallService(memoryDAO, MemoryFtsManager(database))

    companion object {
        const val GLOBAL_MEMORY_ID = MemoryScope.GLOBAL_MEMORY_ID
    }

    private fun MemoryEntity.toModel(score: Float = 0f) = AssistantMemory(
        id = id,
        content = content,
        target = target,
        summary = summary,
        source = source,
        scopeKey = scopeKey,
        conversationId = conversationId,
        updatedAt = updatedAt,
        score = score,
    )

    private fun RecallItem.toModel() = AssistantMemory(
        id = id,
        content = content,
        summary = summary,
        target = target,
        source = source,
        scopeKey = scopeKey,
        conversationId = conversationId,
        updatedAt = updatedAt,
        score = score,
    )

    // ---- 旧 API（兼容现有 UI，返回 durable 记忆） ----

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.filter { it.scopeKey == MemoryScope.DURABLE }
                    .map { it.toModel() }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId)
            .filter { it.scopeKey == MemoryScope.DURABLE }
            .map { it.toModel() }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        getMemoriesOfAssistant(GLOBAL_MEMORY_ID)

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryOps.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory =
        memoryOps.updateMemory(id, content).toModel()

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory =
        memoryOps.storeMemory(assistantId, content, source = "manual").toModel()

    suspend fun deleteMemory(id: Int) {
        memoryOps.deleteMemory(id)
    }

    // ---- 新 API（scope-recall 记忆引擎） ----

    suspend fun storeMemory(
        assistantId: String,
        content: String,
        target: String = MemoryTarget.MEMORY.name,
        summary: String? = null,
        source: String = "tool",
        conversationId: String? = null,
    ): AssistantMemory = memoryOps.storeMemory(assistantId, content, target, summary, source, conversationId).toModel()

    suspend fun updateMemory(
        id: Int,
        content: String,
        summary: String? = null,
        target: String? = null,
    ): AssistantMemory = memoryOps.updateMemory(id, content, summary, target).toModel()

    /** 当前轮召回：基于查询返回 top-k 相关记忆。 */
    suspend fun recallMemories(
        query: String,
        assistantId: String,
        conversationId: String? = null,
        limit: Int = 8,
        minScore: Float = 0.05f,
        enableVector: Boolean = true,
    ): List<AssistantMemory> =
        recallService.prefetch(query, assistantId, conversationId = conversationId, limit = limit, minScore = minScore, enableVector = enableVector)
            .map { it.toModel() }

    /** 按 target 过滤 durable 记忆。 */
    suspend fun getMemoriesByTarget(assistantId: String, target: String): List<AssistantMemory> =
        memoryDAO.getMemoriesByScopeAndTargets(MemoryScope.DURABLE, listOf(target))
            .filter { it.assistantId == assistantId }
            .map { it.toModel() }

    /** 词法搜索 durable 记忆。 */
    suspend fun searchMemories(assistantId: String, query: String, limit: Int = 50): List<AssistantMemory> {
        val hits = MemoryFtsManager(database).search(query, assistantId, MemoryScope.DURABLE, limit = limit)
        if (hits.isEmpty()) return emptyList()
        val ids = hits.map { it.memoryId }
        return memoryDAO.getMemoriesByIds(ids)
            .filterNot { it.isArchived }
            .sortedBy { entity -> ids.indexOf(entity.id) }
            .map { it.toModel() }
    }

    suspend fun appendJournal(assistantId: String, conversationId: String, role: String, content: String) {
        memoryOps.appendJournal(assistantId, conversationId, role, content)
    }

    suspend fun countPendingJournal(): Int = memoryDAO.countUnprocessedJournal()

    suspend fun getJournalOfConversation(conversationId: String): List<me.rerere.rikkahub.data.db.entity.MemoryJournalEntity> =
        memoryDAO.getJournalOfConversation(conversationId)
}
