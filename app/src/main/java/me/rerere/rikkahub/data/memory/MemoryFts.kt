package me.rerere.rikkahub.data.memory

import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity

/** FTS5 词法命中（携带 bm25 原始分，值越小越好）。 */
data class MemoryFtsHit(
    val memoryId: Int,
    val bm25Score: Float?,
)

/**
 * memory_fts 虚拟表同步与词法检索（复用 message_fts 的 simple tokenizer + jieba_query）。
 */
class MemoryFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexMemory(entity: MemoryEntity) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM memory_fts WHERE memory_id = ?", arrayOf(entity.id.toString()))
        if (entity.content.isBlank()) return@withContext
        db.execSQL(
            """
            INSERT INTO memory_fts(content, summary, memory_id, assistant_id, target, scope_key, conversation_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                entity.content,
                entity.summary.orEmpty(),
                entity.id.toString(),
                entity.assistantId,
                entity.target,
                entity.scopeKey,
                entity.conversationId ?: "",
                entity.updatedAt.toString(),
            )
        )
    }

    suspend fun deleteMemory(memoryId: Int) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM memory_fts WHERE memory_id = ?", arrayOf(memoryId.toString()))
    }

    suspend fun deleteAssistant(assistantId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM memory_fts WHERE assistant_id = ?", arrayOf(assistantId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM memory_fts")
    }

    suspend fun rebuild(entities: List<MemoryEntity>) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM memory_fts")
        entities.forEach { entity ->
            if (entity.content.isNotBlank()) {
                db.execSQL(
                    """
                    INSERT INTO memory_fts(content, summary, memory_id, assistant_id, target, scope_key, conversation_id, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        entity.content,
                        entity.summary.orEmpty(),
                        entity.id.toString(),
                        entity.assistantId,
                        entity.target,
                        entity.scopeKey,
                        entity.conversationId ?: "",
                        entity.updatedAt.toString(),
                    )
                )
            }
        }
    }

    /**
     * 词法检索。scopeKey/conversationId 为 null 时不限制该维度。
     * 返回按 bm25 排序的命中（limit 上限）。
     *
     * 说明：
     * - MATCH 作用于表名而非单列，使 content 与 summary 两列都参与检索；
     *   列名 MATCH（如 `content MATCH ...`）只会命中该列，summary 会被静默忽略。
     * - conversationId 只用于约束 local 暂存记忆；durable 记忆在 FTS 中 conversation_id
     *   为空串且跨会话共享，必须一并放行，否则未限定 scopeKey 的召回会漏掉全部 durable 记忆。
     */
    suspend fun search(
        query: String,
        assistantId: String,
        scopeKey: String? = null,
        conversationId: String? = null,
        limit: Int = 50,
    ): List<MemoryFtsHit> = withContext(Dispatchers.IO) {
        // 门控：无有效语义词（空串/寒暄/纯停用词）时不查询
        if (MemoryGating.buildFtsQuery(MemoryGating.semanticQueryTokens(query)).isEmpty()) {
            return@withContext emptyList()
        }

        val conversationFilter = when {
            conversationId == null -> null
            scopeKey == MemoryScope.DURABLE -> null
            else -> "(scope_key = '${MemoryScope.DURABLE}' OR conversation_id = ?)"
        }

        val where = buildList {
            add("assistant_id = ?")
            if (scopeKey != null) add("scope_key = ?")
            conversationFilter?.let(::add)
        }.joinToString(" AND ")

        val args = buildList {
            add(query)
            add(assistantId)
            if (scopeKey != null) add(scopeKey)
            if (conversationFilter != null) add(conversationId!!)
        }

        val cursor = db.query(
            """
            SELECT memory_id, bm25(memory_fts) AS score
            FROM memory_fts
            WHERE memory_fts MATCH jieba_query(?) AND $where
            ORDER BY score
            LIMIT $limit
            """.trimIndent(),
            args.toTypedArray()
        )
        cursor.use { buildHits(it) }
    }

    private fun buildHits(cursor: Cursor): List<MemoryFtsHit> {
        val hits = mutableListOf<MemoryFtsHit>()
        while (cursor.moveToNext()) {
            val id = cursor.getInt(0)
            val score = if (cursor.isNull(1)) null else cursor.getFloat(1)
            hits.add(MemoryFtsHit(id, score))
        }
        return hits
    }
}
