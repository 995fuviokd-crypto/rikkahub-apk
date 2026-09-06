package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class HookEvent {
    @SerialName("pre_send") PRE_SEND,
    @SerialName("post_receive") POST_RECEIVE,
    @SerialName("session_start") SESSION_START,
    @SerialName("session_end") SESSION_END,
}

@Serializable
enum class HookProcessorType {
    @SerialName("shell") SHELL,
    @SerialName("http") HTTP,
    @SerialName("llm") LLM,
}

@Serializable
data class HookConfig(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val enabled: Boolean = true,
    val event: HookEvent = HookEvent.PRE_SEND,
    val processorType: HookProcessorType = HookProcessorType.SHELL,
    val command: String = "",
    val llmPrompt: String? = null,
    val llmModelId: String? = null,
    val timeoutMs: Long = 5000L,
    val failSilently: Boolean = true,
)
