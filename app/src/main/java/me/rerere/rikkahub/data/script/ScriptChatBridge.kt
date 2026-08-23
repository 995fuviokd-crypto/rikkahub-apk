package me.rerere.rikkahub.data.script

import kotlinx.serialization.Serializable

/**
 * Chat 工具运行时桥接接口：把社区脚本的 Chat.* 能力映射到宿主应用数据。
 * 由宿主（RikkaHub）实现并注入 [ScriptRuntime]，脚本执行时可读取/修改本地会话。
 */
@Serializable
data class ScriptChatLite(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
)

@Serializable
data class ScriptMessageLite(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
)

interface ScriptChatBridge {

    fun listChats(
        query: String?,
        match: String?,
        limit: Int?,
        sortBy: String?,
        sortOrder: String?,
    ): List<ScriptChatLite>

    fun findChat(query: String, match: String?, index: Int): ScriptChatLite?

    fun getMessages(chatId: String, order: String?, limit: Int?): List<ScriptMessageLite>

    fun getMessagesRange(chatId: String, order: String?, start: Int, end: Int): List<ScriptMessageLite>

    fun updateTitle(chatId: String, title: String): Boolean

    fun deleteChat(chatId: String): Boolean

    fun createNew(group: String?): ScriptChatLite?

    /** 在指定会话追加一条消息（USER/ASSISTANT），返回新消息 id；会话不存在或内容为空返回 null */
    fun sendMessage(chatId: String, content: String, role: String): String?
}
