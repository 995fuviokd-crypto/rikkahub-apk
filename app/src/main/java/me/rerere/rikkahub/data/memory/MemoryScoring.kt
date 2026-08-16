package me.rerere.rikkahub.data.memory

import java.util.Locale

/**
 * 召回评分（移植自 scope-recall-hermes 的 scoring.py）。
 *
 * 词法/向量/BM25 信号量纲不同，采用加权 RRF 融合 + 最终线性组合，
 * 保持评分可解释（用于统计页与调试）。
 */
object MemoryScoring {

    private val QUERY_STOPWORDS = setOf(
        "what", "which", "when", "where", "who", "whom", "whose", "why", "how",
        "is", "are", "was", "were", "be", "been", "being", "do", "does", "did",
        "should", "could", "would", "can", "our", "your", "their", "my",
        "the", "a", "an", "this", "that", "these", "those", "i", "we", "you",
    )

    private val TARGET_PRIORITY_BONUS = mapOf(
        "USER" to 0.08f,
        "MEMORY" to 0.06f,
        "PROJECT" to 0.055f,
        "OPS" to 0.055f,
        "GENERAL" to -0.04f,
    )

    private fun canonicalTokens(text: String): Set<String> {
        val canonical = mutableSetOf<String>()
        MemoryGating.normalizedTokenSet(MemoryGating.queryTokens(text)).forEach { token ->
            val normalized = token.lowercase(Locale.ROOT)
            if (normalized.isNotEmpty()) canonical.add(normalized)
        }
        return canonical
    }

    private fun canonicalQueryTokens(text: String): Set<String> {
        val canonical = mutableSetOf<String>()
        MemoryGating.normalizedTokenSet(MemoryGating.semanticQueryTokens(text)).forEach { token ->
            val normalized = token.lowercase(Locale.ROOT)
            if (normalized.isNotEmpty()) canonical.add(normalized)
        }
        return canonical
    }

    /** 预计算查询词法 token 集合，供批量评分复用。 */
    fun lexicalQueryTokens(query: String): Set<String> = canonicalQueryTokens(query)

    /** 词法相关分 0..1，仅在有词法/短语/意图信号时返回非零。 */
    fun lexicalScore(query: String, content: String, summary: String?, source: String, target: String): Float =
        lexicalScoreWithTokens(query, content, summary, source, target, canonicalQueryTokens(query))

    /**
     * 词法评分重载：外部已预计算查询 token 集合，供批量召回循环复用，
     * 避免对每条候选重复 token 化 query。
     */
    fun lexicalScoreWithTokens(
        query: String,
        content: String,
        summary: String?,
        source: String,
        target: String,
        queryTokenSet: Set<String>,
    ): Float {
        val haystack = "${summary.orEmpty()}\n$content".lowercase(Locale.ROOT)
        val normalizedQuery = query.lowercase(Locale.ROOT)
        val docTokenSet = canonicalTokens(haystack)

        val informativeQuery = queryTokenSet.filterNot { it in QUERY_STOPWORDS }
        var overlap = 0.0f
        if (informativeQuery.isNotEmpty()) {
            overlap = informativeQuery.count { it in docTokenSet }.toFloat() / informativeQuery.size
        } else if (queryTokenSet.isNotEmpty()) {
            overlap = queryTokenSet.count { it in docTokenSet }.toFloat() / queryTokenSet.size
        }

        val phraseBonus = if (normalizedQuery.isNotEmpty() && normalizedQuery in haystack) 0.35f else 0.0f
        val intentBonus =
            if (MemoryGating.matchedQueryIntentTerms(query, haystack)) 0.18f else 0.0f
        var relevance = overlap * 0.72f + phraseBonus + intentBonus
        if (relevance <= 0.0f) return 0.0f
        val sourceBonus = when {
            source == "builtin-curated" -> 0.18f
            source.startsWith("tool") -> 0.08f
            else -> 0.02f
        }
        val targetBonus = TARGET_PRIORITY_BONUS[target] ?: 0.0f
        relevance += sourceBonus + targetBonus
        return relevance.coerceIn(0.0f, 1.0f)
    }

    /** 将 FTS5 bm25() 值归一化到 0..1（bm25 值越低越好）。 */
    fun bm25ToScore(rawScores: Map<Int, Float?>): Map<Int, Float> {
        val parsed = rawScores.mapNotNull { (id, value) -> value?.let { id to it } }.toMap()
        if (parsed.isEmpty()) return emptyMap()
        val best = parsed.values.minOrNull() ?: return emptyMap()
        val worst = parsed.values.maxOrNull() ?: return emptyMap()
        if (best == worst) return parsed.mapValues { 1.0f }
        val span = worst - best
        return parsed.mapValues { (_, value) -> ((worst - value) / span).coerceIn(0.0f, 1.0f) }
    }

    /** 语义相似度（词法 token 的 Jaccard/含容度）。 */
    fun semanticSimilarity(left: String, right: String): Float {
        val leftTokens = canonicalTokens(left)
        val rightTokens = canonicalTokens(right)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0f
        val intersection = leftTokens.intersect(rightTokens)
        val union = leftTokens.union(rightTokens)
        val jaccard = intersection.size.toFloat() / union.size
        val containment =
            intersection.size.toFloat() / minOf(leftTokens.size, rightTokens.size).coerceAtLeast(1)
        return maxOf(jaccard, containment * 0.82f)
    }

    /** 最终线性组合。 */
    fun combineScores(
        lexical: Float,
        vector: Float,
        bm25: Float,
        lexicalWeight: Float,
        vectorWeight: Float,
        bm25Weight: Float = 0.0f,
    ): Float = (lexical * lexicalWeight + vector * vectorWeight + bm25 * bm25Weight).coerceIn(0.0f, 1.0f)

    /**
     * 加权倒数排名融合。异源排序分值不可比，用 RRF 提升多信号命中。
     * 返回 (id, score) 列表，按分数降序。
     */
    fun reciprocalRankFusion(
        rankedLists: Map<String, List<Int>>,
        weights: Map<String, Float> = emptyMap(),
        k: Int = 60,
        minSignals: Int = 2,
    ): List<Pair<Int, Float>> {
        val scores = mutableMapOf<Int, Float>()
        val signalHits = mutableMapOf<Int, MutableSet<String>>()
        rankedLists.forEach { (signal, ids) ->
            val weight = weights[signal] ?: 1.0f
            if (weight <= 0.0f) return@forEach
            val seen = mutableSetOf<Int>()
            ids.forEachIndexed { index, id ->
                if (id in seen) return@forEachIndexed
                seen.add(id)
                val rank = index + 1
                scores[id] = (scores[id] ?: 0.0f) + weight / (maxOf(1, k) + rank)
                signalHits.getOrPut(id) { mutableSetOf() }.add(signal)
            }
        }
        val minRequired = maxOf(1, minSignals)
        return scores.entries
            .filter { (id) -> (signalHits[id]?.size ?: 0) >= minRequired }
            .sortedWith(compareByDescending<Map.Entry<Int, Float>> { it.value }.thenByDescending { it.key })
            .map { it.key to it.value }
    }
}
