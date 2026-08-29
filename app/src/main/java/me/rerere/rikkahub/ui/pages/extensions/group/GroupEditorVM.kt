package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.repository.GroupRepository
import kotlin.uuid.Uuid

data class ModelInfo(
    val providerName: String,
    val model: Model,
)

class GroupEditorVM(
    id: String?,
    private val repository: GroupRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val groupId: String? = id?.takeIf { it.isNotBlank() }
    val models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val name = MutableStateFlow("")
    val members = MutableStateFlow<List<GroupMember>>(emptyList())
    val orchestratorId = MutableStateFlow<String?>(null)
    val scheduleCron = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(true)
    val saved = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.value
            models.value = settings.providers
                .filter { it.enabled }
                .flatMap { provider ->
                    provider.models
                        .filter { it.type == ModelType.CHAT }
                        .map { ModelInfo(provider.name, it) }
                }
            val group = groupId?.let { repository.getGroupById(it) }
            if (group != null) {
                name.value = group.name
                members.value = group.members
                orchestratorId.value = group.orchestratorId
                scheduleCron.value = group.scheduleCron
            }
            loading.value = false
        }
    }

    fun isMemberSelected(modelId: Uuid): Boolean = members.value.any { it.modelId == modelId }

    fun toggleMember(info: ModelInfo) {
        val current = members.value
        val exists = current.any { it.modelId == info.model.id }
        if (exists) {
            val removed = current.filter { it.modelId != info.model.id }
            members.value = removed
            if (orchestratorId.value != null && removed.none { it.id == orchestratorId.value }) {
                orchestratorId.value = null
            }
        } else {
            val member = GroupMember(
                id = Uuid.random().toString(),
                modelId = info.model.id,
                role = info.model.displayName.ifBlank { info.model.modelId },
            )
            members.value = current + member
        }
    }

    fun setOrchestrator(memberId: String?) {
        orchestratorId.value = memberId
    }

    fun validationError(): String? {
        if (name.value.isBlank()) return "请输入群组名称"
        if (members.value.size < 2) return "请至少选择两个成员（一个主编排器 + 一个工作者）"
        if (orchestratorId.value == null) return "请指定一个主编排器成员"
        return null
    }

    suspend fun save(): Boolean {
        val error = validationError()
        if (error != null) return false
        repository.save(
            Group(
                id = groupId ?: Uuid.random().toString(),
                name = name.value,
                members = members.value,
                orchestratorId = orchestratorId.value,
                scheduleCron = scheduleCron.value?.takeIf { it.isNotBlank() },
            )
        )
        saved.value = true
        return true
    }
}
