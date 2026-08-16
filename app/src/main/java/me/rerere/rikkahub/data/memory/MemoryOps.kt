package me.rerere.rikkahub.data.memory

import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryJournalEntity
import me.rerere.rikkahub.data.model.MemoryTarget

/**
 * 记忆写入与治理（移植自 scope-recall-hermes 的 memory_ops.py 核心）。
 *
 * 写入时执行确定性去重与保守近重复合并；durable 目标跨会话共享，
 * general 作为当前会话本地暂存。journal 记录原始对话轮次作为溯源。
 */
class MemoryOps(
    private val database: AppDatabase,
    private val embedder: MemoryEmbedder = LocalHashEmbedder(),
) {
    companion object {
        const val NEAR_DUPLICATE_THRESHOLD = 0.90f
        const val MAX_NEAR_DUPLICATE_SCAN = 200

        /** journal 只作为溯源暂存，无提炼消费端时防止无限膨胀拖慢数据库 */
        const val JOURNAL_MAX_ROWS = 2000
        const val JOURNAL_TRIM_BATCH = 200
    }

    private val memoryDao get() = database.memoryDao()
    private val memoryFts = MemoryFtsManager(database)

    private fun now() = System.currentTimeMillis()

    /** 写入记忆；命中 exact 去重或近重复合并时复用已有记录。 */
    suspend fun storeMemory(
        assistantId: String,
        content: String,
        target: String = MemoryTarget.MEMORY.name,
        summary: String? = null,
        source: String = "tool",
        conversationId: String? = null,
    ): MemoryEntity {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "memory content must not be empty" }
        val memoryTarget = MemoryTarget.fromString(target)
        val scopeKey = if (memoryTarget.durable) MemoryScope.DURABLE else MemoryScope.LOCAL

        // exact dedupe（同助手同内容，且作用域一致：durable 只匹配 durable，
        // general 只匹配同一会话的 local，避免 GENERAL 暂存误吞 durable 记忆）
        memoryDao.getMemoriesOfAssistant(assistantId)
            .firstOrNull { existing ->
                existing.scopeKey == scopeKey &&
                    (memoryTarget.durable || existing.conversationId == conversationId) &&
                    MemoryGating.dedupKey(existing.content) == MemoryGating.dedupKey(trimmed)
            }
            ?.let { existing ->
                val updated = existing.copy(
                    summary = summary ?: existing.summary,
                    updatedAt = now(),
                    isArchived = false,
                )
                memoryDao.updateMemory(updated)
                memoryFts.indexMemory(updated)
                return updated
            }

        // 保守近重复合并（仅 durable 目标，避免 general 暂存误合并）
        if (memoryTarget.durable) {
            findNearDuplicate(assistantId, trimmed)?.let { existing ->
                val merged = existing.copy(
                    summary = summary ?: existing.summary,
                    updatedAt = now(),
                    isArchived = false,
                )
                memoryDao.updateMemory(merged)
                memoryFts.indexMemory(merged)
                return merged
            }
        }

        val now = now()
        val entity = MemoryEntity(
            assistantId = assistantId,
            content = trimmed,
            target = memoryTarget.name,
            summary = summary,
            source = source,
            scopeKey = scopeKey,
            conversationId = if (memoryTarget.durable) null else conversationId,
            createdAt = now,
            updatedAt = now,
        )
        val id = memoryDao.insertMemory(entity).toInt()
        val saved = entity.copy(id = id)
        memoryFts.indexMemory(saved)
        return saved
    }

    suspend fun updateMemory(
        id: Int,
        content: String,
        summary: String? = null,
        target: String? = null,
    ): MemoryEntity {
        val existing = memoryDao.getMemoryById(id) ?: error("Memory record #$id not found")
        val memoryTarget = target?.let(MemoryTarget::fromString) ?: MemoryTarget.fromString(existing.target)
        val updated = existing.copy(
            content = content,
            summary = summary ?: existing.summary,
            target = memoryTarget.name,
            scopeKey = if (memoryTarget.durable) MemoryScope.DURABLE else MemoryScope.LOCAL,
            conversationId = if (memoryTarget.durable) null else existing.conversationId,
            updatedAt = now(),
        )
        memoryDao.updateMemory(updated)
        memoryFts.indexMemory(updated)
        return updated
    }

    suspend fun deleteMemory(id: Int) {
        memoryDao.deleteMemory(id)
        memoryFts.deleteMemory(id)
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDao.deleteMemoriesOfAssistant(assistantId)
        memoryFts.deleteAssistant(assistantId)
    }

    /** 记录一条对话溯源。写入前裁剪超限的最旧记录，防止 journal 无限膨胀拖慢数据库。 */
    suspend fun appendJournal(
        assistantId: String,
        conversationId: String,
        role: String,
        content: String,
    ) {
        if (content.isBlank()) return
        if (MemoryGating.isTrivial(content)) return
        if (role == "assistant" && content.length > 8000) return
        if (role == "user" && content.length > 4000) return
        memoryDao.insertJournal(
            MemoryJournalEntity(
                assistantId = assistantId,
                conversationId = conversationId,
                role = role,
                content = content,
                createdAt = now(),
            )
        )
        trimJournalIfNeeded()
    }

    private suspend fun trimJournalIfNeeded() {
        if (memoryDao.countUnprocessedJournal() <= JOURNAL_MAX_ROWS) return
        memoryDao.deleteOldestJournal(JOURNAL_TRIM_BATCH)
    }

    private suspend fun findNearDuplicate(assistantId: String, content: String): MemoryEntity? {
        val queryVector = embedder.embed(content)
        var best: MemoryEntity? = null
        var bestScore = 0.0f
        var count = 0
        memoryDao.getScopedMemoriesOfAssistant(assistantId, MemoryScope.DURABLE)
            .asSequence()
            .sortedByDescending { it.updatedAt }
            .take(MAX_NEAR_DUPLICATE_SCAN)
            .forEach { existing ->
                val score = embedder.similarity(queryVector, embedder.embed(existing.content))
                if (score > bestScore) {
                    bestScore = score
                    best = existing
                }
                count++
            }
        return if (bestScore >= NEAR_DUPLICATE_THRESHOLD) best else null
    }
}
