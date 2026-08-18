package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 工作流：由一组有序步骤组成的可复用自动化流程。
 */
@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val description: String = "",
    val steps: List<WorkflowStep> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val stepCount: Int get() = steps.size
}

/**
 * 工作流中的一个步骤节点。
 */
@Serializable
data class WorkflowStep(
    val id: String,
    val name: String,
    val type: StepType,
    val config: StepConfig,
)

@Serializable
enum class StepType {
    @SerialName("text")
    TEXT,

    @SerialName("ai")
    AI,

    @SerialName("shell")
    SHELL,

    @SerialName("http")
    HTTP,

    @SerialName("delay")
    DELAY,
}

/**
 * 步骤配置：sealed 多态，JSON 中使用 "type" 判别字段区分具体类型。
 */
@Serializable
sealed interface StepConfig

@Serializable
@SerialName("text")
data class TextStepConfig(
    val content: String = "",
) : StepConfig

@Serializable
@SerialName("ai")
data class AiStepConfig(
    val assistantId: String = "",
    val prompt: String = "",
) : StepConfig

@Serializable
@SerialName("shell")
data class ShellStepConfig(
    val command: String = "",
    val timeoutMillis: Long = 60_000,
) : StepConfig

@Serializable
@SerialName("http")
data class HttpStepConfig(
    val method: String = "GET",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val timeoutMillis: Long = 30_000,
) : StepConfig

@Serializable
@SerialName("delay")
data class DelayStepConfig(
    val seconds: Int = 1,
) : StepConfig
