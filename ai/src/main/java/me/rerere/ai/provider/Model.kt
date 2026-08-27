package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    val platformAgent: AgentPlatform? = null,
    val agentArguments: List<String> = emptyList(),
    val agentEnvironment: Map<String, String> = emptyMap(),
    val agentSubagent: AgentSubagentConfig? = null,
)

/**
 * 平台 Agent 的子代理（Subagent）委派配置。
 *
 * 引擎自动切换：AUTO 时 DSH 平台复用其自带 subagent 能力，
 * 其余平台由 RikkaHub 内置引擎以本地工具（delegate_subagent）实现委派。
 */
@Serializable
data class AgentSubagentConfig(
    /** 是否启用子代理委派能力 */
    val enabled: Boolean = false,
    /** 引擎选择：auto 自动切换 / built_in RikkaHub 内置 / dsh DSH 自带 */
    val engine: String = ENGINE_AUTO,
    /** 子代理最大嵌套深度（1 = 不允许子代理再委派，防止无限递归） */
    val maxDepth: Int = 1,
    /** 子代理专用模型 ID；为空时跟随当前对话主模型 */
    val modelId: Uuid? = null,
) {
    companion object {
        const val ENGINE_AUTO = "auto"
        const val ENGINE_BUILT_IN = "built_in"
        const val ENGINE_DSH = "dsh"
    }
}

@Serializable
enum class AgentPlatform(
    val cliPackage: String,
    val supportedModes: List<AgentMode> = emptyList(),
) {
    @SerialName("codex")
    CODEX("@zed-industries/codex-acp"),

    @SerialName("claude_code")
    CLAUDE_CODE("@agentclientprotocol/claude-agent-acp"),

    @SerialName("gemini_cli")
    GEMINI_CLI("@google/gemini-cli"),

    @SerialName("anthropic_claude_code")
    ANTHROPIC_CLAUDE_CODE("@anthropic-ai/claude-code"),

    @SerialName("opencode")
    OPENCODE("opencode-ai"),

    @SerialName("deepseek_harness")
    DEEPSEEK_HARNESS("@deepseek-ai/dsh", AgentMode.entries),
}

/**
 * 平台 Agent 支持的运行模式（preset）。DeepSeek Harness 四种官方预设：
 * [STANDARD]（标准）、[CODE]（PTC，Code Mode SDK）、[MINIMAL]（极简）、[CORDIS]（创造）。
 * 通过 `--agent-preset=<preset>` 启动参数透传到 ACP 子进程。
 */
@Serializable
enum class AgentMode(val preset: String) {
    @SerialName("standard")
    STANDARD("standard"),

    @SerialName("code")
    CODE("code"),

    @SerialName("minimal")
    MINIMAL("minimal"),

    @SerialName("cordis")
    CORDIS("cordis"),
}

/** 当前平台 Agent 已配置的运行模式；未显式配置时返回 null（使用平台默认）。 */
fun Model.agentMode(): AgentMode? =
    agentArguments.firstNotNullOfOrNull { arg ->
        if (!arg.startsWith("--agent-preset=")) return@firstNotNullOfOrNull null
        val preset = arg.substringAfter("=")
        AgentMode.entries.firstOrNull { it.preset == preset }
    }

/** 返回设置 [mode] 后的模型副本；传 null 移除显式模式配置（恢复平台默认）。 */
fun Model.withAgentMode(mode: AgentMode?): Model {
    val filtered = agentArguments.filterNot { it.startsWith("--agent-preset=") }
    return copy(
        agentArguments = if (mode == null) filtered else filtered + "--agent-preset=${mode.preset}"
    )
}

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    VIDEO,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}



