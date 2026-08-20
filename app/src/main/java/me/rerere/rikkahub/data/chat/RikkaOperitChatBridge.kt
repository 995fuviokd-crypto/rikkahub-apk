package me.rerere.rikkahub.data.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getMessageCounts
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.operit.OperitChatBridge
import me.rerere.rikkahub.data.operit.OperitChatLite
import me.rerere.rikkahub.data.operit.OperitMessageLite
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 把 Operit Chat.* 工具映射到 RikkaHub 本地会话数据：
 * - listChats/findChat/getMessages 读本地会话与消息（经 Room）；
 * - updateTitle/deleteChat/createNew 提供元数据修改；
 * - switchTo/sendMessage 等依赖 App 界面状态的接口保持受限提示（由运行时处理）。
 * 脚本执行位于后台线程，内部以 runBlocking 桥接 Room 的 suspend 查询。
 */
class RikkaOperitChatBridge(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val conversationRepository: ConversationRepository,
) : OperitChatBridge {

    private fun <T> db(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    override fun listChats(
        query: String?,
        match: String?,
        limit: Int?,
        sortBy: String?,
        sortOrder: String?,
    ): List<OperitChatLite> {
        val counts = db { messageNodeDAO.getMessageCounts() }
            .associate { it.conversationId to it.count }
        val entities = db { conversationDAO.getAll().first() }
        val filtered = entities.filter { matches(it.title, query, match) }
        val sorted = when (sortBy ?: "updatedAt") {
            "createdAt" -> filtered.sortedBy { it.createAt }
            "messageCount" -> filtered.sortedBy { counts[it.id] ?: 0 }
            else -> filtered.sortedBy { it.updateAt }
        }
        val ordered = if (sortOrder == "asc") sorted else sorted.asReversed()
        val take = (limit ?: 50).coerceIn(1, 500)
        return ordered.take(take).map { chatLite(it, counts[it.id] ?: 0) }
    }

    override fun findChat(query: String, match: String?, index: Int): OperitChatLite? {
        val counts = db { messageNodeDAO.getMessageCounts() }
            .associate { it.conversationId to it.count }
        val entities = db { conversationDAO.getAll().first() }
        val matched = entities.filter { matches(it.title, query, match) || it.id == query }
        return matched.getOrNull(index.coerceAtLeast(0))?.let { chatLite(it, counts[it.id] ?: 0) }
    }

    override fun getMessages(chatId: String, order: String?, limit: Int?): List<OperitMessageLite> {
        val conversation = loadConversation(chatId) ?: return emptyList()
        return messagesFromConversation(conversation, order, limit)
    }

    override fun getMessagesRange(
        chatId: String,
        order: String?,
        start: Int,
        end: Int,
    ): List<OperitMessageLite> {
        val conversation = loadConversation(chatId) ?: return emptyList()
        val messages = messagesFromConversation(conversation, order, null)
        if (start < 0 || end < start) return emptyList()
        val from = start.coerceAtMost(messages.size)
        val to = (end + 1).coerceIn(from, messages.size)
        return messages.subList(from, to)
    }

    override fun updateTitle(chatId: String, title: String): Boolean {
        return db {
            val entity = conversationDAO.getConversationById(chatId) ?: return@db false
            val updated = entity.copy(title = title.take(300))
            conversationDAO.update(updated)
            true
        }
    }

    override fun deleteChat(chatId: String): Boolean {
        return db {
            val id = runCatching { Uuid.parse(chatId) }.getOrNull() ?: return@db false
            val conversation = conversationRepository.getConversationById(id) ?: return@db false
            conversationRepository.deleteConversation(conversation)
            true
        }
    }

    override fun createNew(group: String?): OperitChatLite? {
        return db {
            val conversation = Conversation(
                assistantId = DEFAULT_ASSISTANT_ID,
                title = group.orEmpty(),
                messageNodes = emptyList(),
                createAt = Instant.now(),
                updateAt = Instant.now(),
            )
            conversationRepository.insertConversation(conversation)
            OperitChatLite(
                id = conversation.id.toString(),
                title = conversation.title,
                createdAt = conversation.createAt.toEpochMilli(),
                updatedAt = conversation.updateAt.toEpochMilli(),
                messageCount = 0,
            )
        }
    }

    private fun loadConversation(chatId: String): Conversation? {
        val id = runCatching { Uuid.parse(chatId) }.getOrNull() ?: return null
        return db<Conversation?> { conversationRepository.getConversationById(id) }
    }

    private fun messagesFromConversation(
        conversation: Conversation,
        order: String?,
        limit: Int?,
    ): List<OperitMessageLite> {
        val nodes = conversation.messageNodes.filter { it.messages.isNotEmpty() }
        var messages = nodes.mapNotNull { node ->
            runCatching { node.currentMessage }.getOrNull()
        }.map { msg ->
            OperitMessageLite(
                id = msg.id.toString(),
                role = msg.role.name.lowercase(),
                content = msg.toText(),
                timestamp = runCatching {
                    msg.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                }.getOrDefault(0L),
            )
        }
        val descending = order == "desc"
        if (descending) messages = messages.asReversed()
        val take = limit?.coerceAtLeast(0)
        if (take != null) messages = messages.take(take)
        return messages
    }

    private fun matches(title: String, query: String?, match: String?): Boolean {
        if (query.isNullOrBlank()) return true
        return when (match) {
            "exact" -> title == query
            "regex" -> runCatching { Regex(query).containsMatchIn(title) }.getOrDefault(false)
            else -> title.contains(query, ignoreCase = true)
        }
    }

    private fun chatLite(entity: ConversationEntity, messageCount: Int): OperitChatLite {
        return OperitChatLite(
            id = entity.id,
            title = entity.title,
            createdAt = entity.createAt,
            updatedAt = entity.updateAt,
            messageCount = messageCount,
        )
    }
}
