package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlin.uuid.Uuid

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(assistant)
                )
            )
        }
    }

    /**
     * 角色卡导入：助手与世界书在同一次 settings 更新中落库，
     * 避免两次异步 update 基于过期快照互相覆盖。
     */
    fun importCharacterCard(assistant: Assistant, lorebook: Lorebook?) {
        viewModelScope.launch {
            val current = settings.value
            var newAssistant = assistant
            var newLorebooks = current.lorebooks
            if (lorebook != null) {
                newAssistant = assistant.copy(lorebookIds = setOf(lorebook.id))
                newLorebooks = current.lorebooks + lorebook
            }
            settingsStore.update(
                current.copy(
                    assistants = current.assistants.plus(newAssistant),
                    lorebooks = newLorebooks,
                )
            )
        }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            cleanupAssistantFiles(assistant)

            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.filter { it.id != assistant.id }
                )
            )
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    fun createAssistantFromWizard(state: me.rerere.rikkahub.ui.pages.assistant.WizardState) {
        viewModelScope.launch {
            val current = settings.value
            val assistantId = Uuid.random()
            val defaultOutputStyleId = current.outputStyles.firstOrNull { it.name == "Default" }?.id
            val systemPrompt = buildString {
                if (state.description.isNotBlank()) {
                    append(state.description)
                    appendLine()
                }
                append(state.selectedPresetTemplateInstructions())
            }
            val newAssistant = Assistant(
                id = assistantId,
                name = state.name,
                systemPrompt = systemPrompt,
                activeOutputStyleId = defaultOutputStyleId,
            )
            val lorebookEntries = state.exampleDialogs.map { dialog ->
                PromptInjection.RegexInjection(
                    name = "Example: ${dialog.charMessage.take(30)}",
                    content = "User: ${dialog.userMessage}\nAssistant: ${dialog.charMessage}",
                    keywords = listOf(dialog.userMessage.take(20)),
                    priority = 0,
                    position = me.rerere.rikkahub.data.model.InjectionPosition.AFTER_SYSTEM_PROMPT,
                    role = me.rerere.ai.core.MessageRole.USER,
                    constantActive = false,
                )
            }
            val lorebook = if (lorebookEntries.isNotEmpty()) {
                Lorebook(
                    id = Uuid.random(),
                    name = "${state.name} - Examples",
                    description = "Auto-generated from creation wizard",
                    entries = lorebookEntries,
                )
            } else null
            val finalAssistant = if (lorebook != null) {
                newAssistant.copy(lorebookIds = setOf(lorebook.id))
            } else {
                newAssistant
            }
            settingsStore.update(
                current.copy(
                    assistants = current.assistants + finalAssistant,
                    lorebooks = if (lorebook != null) current.lorebooks + lorebook else current.lorebooks,
                )
            )
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }

    fun copyAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(copiedAssistant)
                )
            )
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }
}

private fun WizardState.selectedPresetTemplateInstructions(): String = when (selectedPreset) {
    "General Chat" -> "Be friendly and helpful in your conversations."
    "Learning Companion" -> "Explain concepts clearly, ask follow-up questions, and use TODO(human) markers when the user should practice."
    "Programming Assistant" -> "Help with coding tasks. Provide clear explanations, best practices, and concise code examples."
    "Translator" -> "Translate accurately between languages, preserving tone and context."
    "Creative Writer" -> "Help with creative writing. Be imaginative, suggest ideas, and provide constructive feedback."
    else -> ""
}
