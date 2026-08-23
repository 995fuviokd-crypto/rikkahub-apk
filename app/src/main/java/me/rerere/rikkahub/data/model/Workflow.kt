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
    val graph: WorkflowGraph? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val lastExecutionTime: Long = 0,
    val lastExecutionStatus: ExecutionStatus? = null,
    val totalExecutions: Long = 0,
    val successfulExecutions: Long = 0,
    val failedExecutions: Long = 0,
) {
    val stepCount: Int get() = graph?.nodeCount ?: steps.size

    /** 图结构为空时回退到旧 steps 转换的图。 */
    val effectiveGraph: WorkflowGraph get() = graph ?: legacyStepsToGraph(steps)

    /** 带执行统计的副本（执行统计字段）。 */
    fun withStats(
        status: ExecutionStatus,
        timestamp: Long,
        success: Boolean,
    ): Workflow = copy(
        lastExecutionTime = timestamp,
        lastExecutionStatus = status,
        totalExecutions = totalExecutions + 1,
        successfulExecutions = successfulExecutions + if (success) 1 else 0,
        failedExecutions = failedExecutions + if (success) 0 else 1,
    )

    /** 导出持久化用的统计快照。 */
    fun stats(): WorkflowStats = WorkflowStats(
        lastExecutionTime = lastExecutionTime,
        lastExecutionStatus = lastExecutionStatus,
        totalExecutions = totalExecutions,
        successfulExecutions = successfulExecutions,
        failedExecutions = failedExecutions,
    )
}

/**
 * 提取 [StepConfig] 中引用的上游节点 id（`{{node.<id>.output}}`）。
 * 执行引擎用这些引用建立隐式依赖，保证被引用节点先于当前节点执行。
 */
private val NODE_REFERENCE_PATTERN = Regex("""\{\{\s*node\.([\w-]+)\.output(?:\|len)?\s*\}\}""")

fun StepConfig.nodeReferenceIds(): Set<String> {
    val refs = mutableSetOf<String>()
    fun scan(text: String) {
        if (text.isBlank()) return
        NODE_REFERENCE_PATTERN.findAll(text).forEach { refs.add(it.groupValues[1]) }
    }
    when (this) {
        is TextStepConfig -> scan(content)
        is AiStepConfig -> scan(prompt)
        is ShellStepConfig -> scan(command)
        is HttpStepConfig -> {
            scan(url)
            scan(body)
            headers.values.forEach { scan(it) }
        }
        is IfStepConfig -> scan(condition)
        is ForStepConfig -> {
            scan(itemsSource)
            scan(prompt)
        }
        is ExtractStepConfig -> {
            scan(source)
            scan(expression)
            others.forEach { scan(it) }
        }
        is OutputStepConfig -> scan(template)
        is StartStepConfig, is EndStepConfig, is MergeStepConfig, is DelayStepConfig -> Unit
    }
    return refs
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

@Serializable
@SerialName("start")
data class StartStepConfig(val _unused: Boolean = false) : StepConfig

@Serializable
@SerialName("end")
data class EndStepConfig(val _unused: Boolean = false) : StepConfig

@Serializable
@SerialName("if")
data class IfStepConfig(
    val condition: String = "",
) : StepConfig

@Serializable
@SerialName("for")
data class ForStepConfig(
    val itemsSource: String = "",
    val prompt: String = "",
    val assistantId: String = "",
) : StepConfig

@Serializable
@SerialName("merge")
data class MergeStepConfig(val _unused: Boolean = false) : StepConfig

/**
 * 提取节点：从上游输出或输入变量中提取数据。
 */
@Serializable
@SerialName("extract")
data class ExtractStepConfig(
    val mode: ExtractMode = ExtractMode.REGEX,
    val source: String = "",
    val expression: String = "",
    val group: Int = 0,
    val defaultValue: String = "",
    val startIndex: Int = 0,
    val length: Int = 0,
    val others: List<String> = emptyList(),
    val separator: String = "",
) : StepConfig

@Serializable
@SerialName("output")
data class OutputStepConfig(
    val template: String = "",
) : StepConfig

/**
 * 工作流最近的执行状态。
 */
@Serializable
enum class ExecutionStatus {
    @SerialName("success")
    SUCCESS,

    @SerialName("failed")
    FAILED,

    @SerialName("running")
    RUNNING,
}

/**
 * 工作流执行统计（持久化到 workflows.stats_json）。
 */
@Serializable
data class WorkflowStats(
    val lastExecutionTime: Long = 0,
    val lastExecutionStatus: ExecutionStatus? = null,
    val totalExecutions: Long = 0,
    val successfulExecutions: Long = 0,
    val failedExecutions: Long = 0,
)
