package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

// 全部属性为 val 且每次更新都生成新实例（data class copy），满足 @Immutable 语义：
// Compose 据此在流式更新时按引用相等跳过未变化的消息节点，避免每 delta 全量重组。
@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // 历史压缩状态：记录滚动摘要与已压缩消息 id；历史消息本体保留在 messageNodes 中不删除
    val compression: ConversationCompression? = null,
    @Transient
    val newConversation: Boolean = false
) {
    /**
     * 参与上下文的当前消息：已压缩的历史被排除（其内容以 compression.summary 代表）。
     */
    val activeMessages: List<UIMessage>
        get() {
            val compressed = compression?.compressedMessageIds ?: return currentMessages
            if (compressed.isEmpty()) return currentMessages
            return currentMessages.filter { it.id !in compressed }
        }

    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()
        var changed = false

        messages.forEachIndexed { index, message ->
            // 该 index 的节点是否已存在。快速短路只对既有节点生效：
            // 对不存在的 index 新建节点时，节点内容就是 message 本身（同一对象恒相等），
            // 若同样短路会导致新节点永不加入，流式生成的首条 assistant 消息永远无法显示。
            val nodeExists = index < newNodes.size
            val node = if (nodeExists) newNodes[index] else message.toMessageNode()

            if (nodeExists) {
                // 快速短路：消息已存在且内容未变化时直接跳过。流式 emit 携带完整历史，
                // 只有末条消息在变化，避免为每条历史消息重复创建列表并复制节点导致卡顿。
                // 先做引用相等短路 (O(1))：流式期间未变化的历史消息是同一对象，
                // 避免 data class 深比较递归扫描全部 parts 全文字符串 (长对话下开销巨大)
                val existingIndex = node.messages.indexOfFirst { it.id == message.id }
                val existing = if (existingIndex >= 0) node.messages[existingIndex] else null
                if (existing != null && (existing === message || existing == message)) {
                    return@forEachIndexed
                }
            }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            val existingIndex = newMessages.indexOfFirst { it.id == message.id }
            if (existingIndex >= 0) {
                newMessages[existingIndex] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
                changed = true
            } else if (newNode != node) {
                newNodes[index] = newNode
                changed = true
            }
        }

        // 内容无任何变化时返回 this，保证 StateFlow 的相等性判断能跳过下游重组
        return if (changed) {
            this.copy(messageNodes = newNodes)
        } else {
            this
        }
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
@Immutable
data class ConversationCompression(
    // 滚动累积的历史摘要（覆盖全部 compressedMessageIds 对应内容）
    val summary: String,
    // 已被压缩、不再直接进入上下文的消息 id；消息本体仍保留在 messageNodes 中供 UI 查看
    val compressedMessageIds: Set<Uuid> = emptySet(),
    val compressedCount: Int = 0,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
)

@Serializable
@Immutable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
