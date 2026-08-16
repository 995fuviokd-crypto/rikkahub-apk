package me.rerere.rikkahub.data.memory

import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity

/** 召回结果条目（带评分组件，便于统计与调试展示）。 */
data class RecallItem(
    val id: Int,
    val content: String,
    val summary: String?,
    val target: String,
    val source: String,
    val scopeKey: String,
    val conversationId: String?,
    val updatedAt: Long,
    val score: Float = 0f,
    val lexicalScore: Float = 0f,
    val vectorScore: Float = 0f,
    val bm25Score: Float = 0f,
    val rrfScore: Float = 0f,
    val intentMatched: Boolean = false,
    val currentStateRank: Int = 0,
)

/**
 * 当前轮召回服务（移植自 scope-recall-hermes 的 recall.py 核心）。
 *
 * 基于当前查询进行混合检索：词法（FTS5/BM25）+ 向量（本地哈希或 LLM 嵌入），
 * 加权 RRF 融合后线性组合排序，保守门控避免空/寒暄查询浪费。
 */
class MemoryRecallService(
    private val memoryDao: MemoryDAO,
    private val memoryFts: MemoryFtsManager,
    private val embedder: MemoryEmbedder = LocalHashEmbedder(),
) {
    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val DEFAULT_CANDIDATE_POOL = 50
        const val RECENT_FALLBACK = 30

        val LEXICAL_WEIGHT = 0.45f
        val VECTOR_WEIGHT = 0.40f
        val BM25_WEIGHT = 0.15f
    }

    /**
     * 基于当前用户查询召回相关记忆。
     *
     * @param query 当前用户消息
     * @param assistantId 记忆归属 id（全局或助手）
     * @param scopeKey null 表示不限制作用域（durable + local）
     * @param conversationId 会话 id，用于 local 暂存过滤；null 表示不限制
     * @param limit 返回条数上限
     * @param minScore 最终分阈值
     */
    suspend fun prefetch(
        query: String,
        assistantId: String,
        scopeKey: String? = null,
        conversationId: String? = null,
        limit: Int = 8,
        minScore: Float = 0.05f,
        enableVector: Boolean = true,
    ): List<RecallItem> {
        if (MemoryGating.shouldSkipRetrieval(query, MIN_QUERY_LENGTH)) return emptyList()
        if (limit <= 0) return emptyList()

        // 1) 词法候选（FTS5 + bm25）
        val ftsHits = memoryFts.search(query, assistantId, scopeKey, conversationId, DEFAULT_CANDIDATE_POOL)
        val bm25Map = MemoryScoring.bm25ToScore(ftsHits.associate { it.memoryId to it.bm25Score })

        // 2) 候选池 = 词法命中 ∪ 最近记录（向量信号来源）；关闭向量时仅词法命中
        // recentIds 只加载一次，供候选池与向量嵌入复用，避免重复查库
        val recentIds = if (enableVector) {
            loadRecentIds(assistantId, scopeKey, conversationId, RECENT_FALLBACK)
        } else {
            emptyList()
        }
        val ftsIds = ftsHits.map { it.memoryId }.toSet()
        val candidateIds = if (enableVector) {
            (ftsIds + recentIds.filterNot { it in ftsIds }).take(DEFAULT_CANDIDATE_POOL)
        } else {
            ftsIds.take(DEFAULT_CANDIDATE_POOL)
        }
        if (candidateIds.isEmpty()) return emptyList()

        val entities = memoryDao.getMemoriesByIds(candidateIds.toList())
            .filterNot { it.isArchived }
            .associateBy { it.id }

        // 3) 计算各信号分
        val lexicalOrdered = mutableListOf<Int>()
        val vectorOrdered = mutableListOf<Int>()
        val scored = mutableListOf<Triple<Int, Float, Float>>() // id, lexical, vector

        // 向量嵌入只对精简候选集执行（FTS 命中按 bm25 靠前的子集 + recent 补足），
        // 避免对全部候选逐条 embed 造成发送链路卡顿
        val vectorCandidates = if (enableVector) {
            val ftsTop = ftsHits.take(20).map { it.memoryId }
            val recentTop = recentIds.filterNot { it in ftsTop }
            (ftsTop + recentTop).distinct()
        } else {
            emptyList()
        }
        val vectorIdSet = vectorCandidates.toSet()
        // 查询 token 集合只计算一次，供所有候选的词法评分复用
        val queryTokenSet = MemoryScoring.lexicalQueryTokens(query)

        if (enableVector) {
            val queryVector = embedder.embed(query)
            ftsHits.forEach { hit ->
                val entity = entities[hit.memoryId] ?: return@forEach
                val lexical = MemoryScoring.lexicalScoreWithTokens(query, entity.content, entity.summary, entity.source, entity.target, queryTokenSet)
                lexicalOrdered.add(entity.id)
                if (entity.id in vectorIdSet) {
                    val vector = embedder.similarity(queryVector, embedder.embed("${entity.summary.orEmpty()}\n${entity.content}"))
                    scored.add(Triple(entity.id, lexical, vector))
                } else {
                    scored.add(Triple(entity.id, lexical, 0.0f))
                }
            }
            recentIds
                .filter { it !in ftsIds }
                .filter { it in vectorIdSet }
                .forEach { id ->
                    val entity = entities[id] ?: return@forEach
                    val vector = embedder.similarity(queryVector, embedder.embed("${entity.summary.orEmpty()}\n${entity.content}"))
                    scored.add(Triple(id, 0.0f, vector))
                }
            scored.sortedByDescending { it.third }.forEach { (id, _, _) -> vectorOrdered.add(id) }
        } else {
            ftsHits.forEach { hit ->
                val entity = entities[hit.memoryId] ?: return@forEach
                val lexical = MemoryScoring.lexicalScoreWithTokens(query, entity.content, entity.summary, entity.source, entity.target, queryTokenSet)
                lexicalOrdered.add(entity.id)
                scored.add(Triple(entity.id, lexical, 0.0f))
            }
        }

        // 4) 加权 RRF 融合
        val rrf = MemoryScoring.reciprocalRankFusion(
            rankedLists = mapOf("lexical" to lexicalOrdered, "vector" to vectorOrdered),
            weights = mapOf("lexical" to 0.5f, "vector" to 0.5f),
            k = 60,
            minSignals = 1,
        ).toMap()

        // 5) 线性组合 + 排序
        val ranked = scored.mapNotNull { (id, lexical, vector) ->
            val entity = entities[id] ?: return@mapNotNull null
            val bm25 = bm25Map[id] ?: 0.0f
            val finalScore = MemoryScoring.combineScores(
                lexical = lexical,
                vector = vector,
                bm25 = bm25,
                lexicalWeight = LEXICAL_WEIGHT,
                vectorWeight = VECTOR_WEIGHT,
                bm25Weight = BM25_WEIGHT,
            )
            if (finalScore < minScore && lexical <= 0.0f) return@mapNotNull null
            RecallItem(
                id = entity.id,
                content = entity.content,
                summary = entity.summary,
                target = entity.target,
                source = entity.source,
                scopeKey = entity.scopeKey,
                conversationId = entity.conversationId,
                updatedAt = entity.updatedAt,
                score = finalScore,
                lexicalScore = lexical,
                vectorScore = vector,
                bm25Score = bm25,
                rrfScore = rrf[id] ?: 0.0f,
                intentMatched = MemoryGating.matchedQueryIntentTerms(query, "${entity.summary.orEmpty()}\n${entity.content}"),
                currentStateRank = if (MemoryGating.queriesCurrentState(query)) 1 else 0,
            )
        }

        return ranked.sortedWith(
            compareByDescending<RecallItem> { it.currentStateRank }
                .thenByDescending { it.score }
                .thenByDescending { it.intentMatched }
                .thenByDescending { it.lexicalScore }
                .thenByDescending { it.updatedAt }
                .thenByDescending { it.id }
        ).take(limit)
    }

    private suspend fun loadRecentIds(
        assistantId: String,
        scopeKey: String?,
        conversationId: String?,
        limit: Int,
    ): List<Int> {
        if (scopeKey != null) {
            return memoryDao.getScopedMemoriesOfAssistant(assistantId, scopeKey, conversationId)
                .asSequence()
                .sortedByDescending { it.updatedAt }
                .take(limit)
                .map { it.id }
                .toList()
        }
        return memoryDao.getMemoriesOfAssistant(assistantId)
            .asSequence()
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .map { it.id }
            .toList()
    }
}
