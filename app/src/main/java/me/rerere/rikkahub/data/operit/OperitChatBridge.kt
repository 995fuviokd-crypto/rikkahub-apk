package me.rerere.rikkahub.data.operit

import kotlinx.serialization.Serializable

/**
 * Operit Chat 工具运行时桥接接口：把社区脚本的 Chat.* 能力映射到宿主应用数据。
 * 由宿主（RikkaHub）实现并注入 [OperitScriptRuntime]，脚本执行时可读取/修改本地会话。
 */
@Serializable
data class OperitChatLite(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
)

@Serializable
data class OperitMessageLite(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
)

interface OperitChatBridge {

    fun listChats(
        query: String?,
        match: String?,
        limit: Int?,
        sortBy: String?,
        sortOrder: String?,
    ): List<OperitChatLite>

    fun findChat(query: String, match: String?, index: Int): OperitChatLite?

    fun getMessages(chatId: String, order: String?, limit: Int?): List<OperitMessageLite>

    fun getMessagesRange(chatId: String, order: String?, start: Int, end: Int): List<OperitMessageLite>

    fun updateTitle(chatId: String, title: String): Boolean

    fun deleteChat(chatId: String): Boolean

    fun createNew(group: String?): OperitChatLite?
}
