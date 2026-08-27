package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.ai.agent.AcpRuntime
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale

class TranslationHandler(
    private val providerManager: ProviderManager,
    private val acpRuntime: AcpRuntime? = null,
    private val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? = null,
) {
    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null,
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (model.platformAgent != null) {
            // 平台 Agent 翻译：走 ACP 通道（agent 侧自行处理），无工作区时取默认 READY 工作区
            val runtime = acpRuntime ?: error("Platform agent model requires AcpRuntime")
            val root = resolveDefaultWorkspaceRoot()
                ?: error("Platform agent translation requires a bound workspace")
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )
            var messages = listOf(UIMessage.user(prompt))
            val streamChunkHandler = StreamChunkHandler(model)
            runtime.streamText(
                model = model,
                messages = messages,
                workspaceRoot = root,
                workspaceCwd = null,
                conversationId = null,
            ).collect { chunk ->
                messages = streamChunkHandler.handle(messages, chunk)
                val translatedText = messages.lastOrNull()?.toText() ?: ""
                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
            return@flow
        }

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            val streamChunkHandler = StreamChunkHandler(model)

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = streamChunkHandler.handle(messages, chunk)
                val translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH)),
                                )
                            },
                        )
                    ),
                ),
            )
            val translatedText = result.message.toText()

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun resolveDefaultWorkspaceRoot(): String? {
        val repo = workspaceRepository ?: return null
        return repo.listFlow().first()
            .firstOrNull { it.shellStatus == WorkspaceShellStatus.READY.name }
            ?.root
    }
}
