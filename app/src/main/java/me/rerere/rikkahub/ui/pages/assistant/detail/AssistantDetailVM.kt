package me.rerere.rikkahub.ui.pages.assistant.detail

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.plugin.PluginSkillInfo
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val pluginManager: PluginManager,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    private val _pluginSkills = MutableStateFlow<List<PluginSkillInfo>>(emptyList())
    val pluginSkills = _pluginSkills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
        // 市场安装的 skill 类型插件：随已启用插件集合联动刷新，供「扩展管理-技能」页与本地技能合并展示
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                _pluginSkills.value = pluginManager.listPluginSkills(settings.enabledPlugins)
            }
        }
    }

    /** 切换插件技能（type=skill 插件）的启用状态，与插件市场的启用开关保持联动 */
    fun togglePluginSkill(pluginId: String) {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            val enabled = if (pluginId in settings.enabledPlugins) {
                settings.enabledPlugins - pluginId
            } else {
                settings.enabledPlugins + pluginId
            }
            settingsStore.update { it.copy(enabledPlugins = enabled) }
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    val memories = assistant
        .flatMapLatest { currentAssistant ->
            if (currentAssistant.useGlobalMemory) {
                memoryRepository.getGlobalMemoriesFlow()
            } else {
                memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistantTags = tags
                )
            )
            update(
                assistant.value.copy(
                    tags = tagIds.toList()
                )
            )
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            cleanupUnusedTags()
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch {
            val settings = settings.value
            val validTagIds = settings.assistantTags.map { it.id }.toSet()

            // 清理 assistant 中的无效 tag id
            val cleanedAssistants = settings.assistants.map { assistant ->
                val validTags = assistant.tags.filter { tagId ->
                    validTagIds.contains(tagId)
                }
                if (validTags.size != assistant.tags.size) {
                    assistant.copy(tags = validTags)
                } else {
                    assistant
                }
            }

            // 获取清理后的 assistant 中使用的 tag id
            val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

            // 清理未使用的 tags
            val cleanedTags = settings.assistantTags.filter { tag ->
                usedTagIds.contains(tag.id)
            }

            // 检查是否需要更新
            val needUpdateAssistants = cleanedAssistants != settings.assistants
            val needUpdateTags = cleanedTags.size != settings.assistantTags.size

            if (needUpdateAssistants || needUpdateTags) {
                settingsStore.update(
                    settings = settings.copy(
                        assistants = cleanedAssistants,
                        assistantTags = cleanedTags
                    )
                )
            }
        }
    }

    fun update(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            checkAvatarDelete(old = it, new = assistant) // 删除旧头像
                            checkBackgroundDelete(old = it, new = assistant) // 删除旧背景
                            assistant
                        } else {
                            it
                        }
                    })
            )
        }
    }

    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.storeMemory(
                assistantId = memoryAssistantId,
                content = memory.content,
                target = memory.target,
                summary = memory.summary,
                source = "manual",
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.updateMemory(
                id = memory.id,
                content = memory.content,
                summary = memory.summary,
                target = memory.target,
            )
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id = memory.id)
        }
    }

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            filesManager.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        val oldBackground = old.background
        val newBackground = new.background

        if (oldBackground != null && oldBackground != newBackground) {
            try {
                val oldUri = oldBackground.toUri()
                if (oldUri.scheme == "content" || oldUri.scheme == "file") {
                    filesManager.deleteChatFiles(listOf(oldUri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $oldBackground", e)
            }
        }
    }
}
