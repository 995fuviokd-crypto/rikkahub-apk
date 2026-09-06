package me.rerere.rikkahub.ui.pages.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.memory.MemoryScope
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryTarget
import me.rerere.rikkahub.data.repository.MemoryRepository

/**
 * Operit / scope-recall-hermes 兼容的记忆数据交换格式：
 * 字段与 truth store 对齐（content/target/summary/source/scope_key/conversation_id/updated_at），
 * 支持跨客户端导入导出。顶层带 version 便于未来演进。
 */
@Serializable
data class MemoryExportWrapper(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val memories: List<MemoryExportItem> = emptyList(),
)

@Serializable
data class MemoryExportItem(
    val content: String,
    val target: String = MemoryTarget.MEMORY.name,
    val summary: String? = null,
    val source: String = "manual",
    val scopeKey: String = me.rerere.rikkahub.data.memory.MemoryScope.DURABLE,
    val conversationId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val assistantId: String = me.rerere.rikkahub.data.repository.MemoryRepository.GLOBAL_MEMORY_ID,
) {
    fun isValid() = content.isNotBlank()
}

/**
 * 全局记忆库 ViewModel：跨助手查看所有 durable 记忆，
 * 支持按目标类型过滤与关键词搜索，并提供 Operit 生态兼容的记忆 JSON 导入/导出
 * （决策：记忆画布 → 全局 + 过滤）。
 */
class MemoryLibraryVM(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val allMemories = memoryRepository.getAllMemoriesFlow()

    val selectedTarget = MutableStateFlow<String?>(null)
    val searchQuery = MutableStateFlow("")
    val importResult = MutableStateFlow<String?>(null)
    val showLocalScope = MutableStateFlow(false)

    private val ftsResults = MutableStateFlow<List<AssistantMemory>>(emptyList())

    val filtered: StateFlow<List<AssistantMemory>> = combine(
        allMemories,
        selectedTarget,
        searchQuery,
        showLocalScope,
        ftsResults,
    ) { memories, target, query, showLocal, fts ->
        val scopeFiltered = memories.filter { showLocal || it.scopeKey == MemoryScope.DURABLE }
        val targetFiltered = if (target == null) scopeFiltered
            else scopeFiltered.filter { it.target.equals(target, ignoreCase = true) }
        if (query.isBlank()) {
            targetFiltered.sortedByDescending { it.updatedAt }
        } else {
            val ftsIds = fts.map { it.id }.toSet()
            targetFiltered.filter { it.id in ftsIds }
                .sortedBy { fts.map { m -> m.id }.indexOf(it.id) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            searchQuery.collectLatest { query ->
                if (query.isBlank()) {
                    ftsResults.value = emptyList()
                } else {
                    ftsResults.value = memoryRepository.searchMemories(
                        assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
                        query = query,
                        limit = 50,
                    )
                }
            }
        }
    }

    fun delete(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(memory.id)
        }
    }

    fun targets(): List<MemoryTarget> = MemoryTarget.entries.filter { it.durable }

    /** 导出全部 durable 记忆为 Operit 兼容 JSON。 */
    suspend fun exportJson(): String = json.encodeToString<MemoryExportWrapper>(
        MemoryExportWrapper(
            memories = memoryRepository.getAllMemories().map { memory ->
                MemoryExportItem(
                    content = memory.content,
                    target = memory.target,
                    summary = memory.summary,
                    source = memory.source,
                    scopeKey = memory.scopeKey,
                    conversationId = memory.conversationId,
                    updatedAt = memory.updatedAt,
                    assistantId = memory.assistantId.ifBlank { MemoryRepository.GLOBAL_MEMORY_ID },
                )
            },
        ),
    )

    /** 导入 Operit 兼容 JSON，逐条通过 storeMemory 自动去重合并。 */
    fun importJson(text: String) {
        viewModelScope.launch {
            runCatching {
                val wrapper = json.decodeFromString<MemoryExportWrapper>(text)
                if (wrapper.version !in 1..1) error("不支持的记忆数据版本：${wrapper.version}")
                val items = wrapper.memories.filter { it.isValid() }
                var imported = 0
                for (item in items) {
                    val target = MemoryTarget.fromString(item.target)
                    memoryRepository.storeMemory(
                        assistantId = item.assistantId.ifBlank { MemoryRepository.GLOBAL_MEMORY_ID },
                        content = item.content,
                        target = target.name,
                        summary = item.summary,
                        source = item.source.ifBlank { "operit-import" },
                        conversationId = if (target.durable) null else item.conversationId,
                    )
                    imported++
                }
                "导入完成：共处理 $imported 条（重复内容自动去重合并）"
            }.onSuccess { importResult.value = it }
                .onFailure { e ->
                    importResult.value = "导入失败：${e.message ?: e.javaClass.simpleName}"
                }
        }
    }

    fun consumeImportResult() = importResult.update { null }
}