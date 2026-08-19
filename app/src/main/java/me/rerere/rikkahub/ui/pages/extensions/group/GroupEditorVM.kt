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
import me.rerere.rikkahub.data.model.GroupMode
import me.rerere.rikkahub.data.repository.GroupRepository
import kotlin.uuid.Uuid

data class ModelInfo(
    val providerName: String,
    val model: Model,
)

class GroupEditorVM(
    private val id: String?,
    private val repository: GroupRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val name = MutableStateFlow("")
    val mode = MutableStateFlow(GroupMode.DEBATE)
    val members = MutableStateFlow<List<GroupMember>>(emptyList())
    val orchestratorId = MutableStateFlow<String?>(null)
    val debateRounds = MutableStateFlow(3)
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
            val group = id?.let { repository.getGroupById(it) }
            if (group != null) {
                name.value = group.name
                mode.value = group.mode
                members.value = group.members
                orchestratorId.value = group.orchestratorId
                debateRounds.value = group.debateRounds
            }
            loading.value = false
        }
    }

    fun setMode(newMode: GroupMode) {
        mode.value = newMode
        if (newMode != GroupMode.ORCHESTRATOR_WORKER) {
            orchestratorId.value = null
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

    fun setRole(memberId: String, role: String) {
        members.value = members.value.map {
            if (it.id == memberId) it.copy(role = role) else it
        }
    }

    fun setOrchestrator(memberId: String?) {
        orchestratorId.value = memberId
    }

    fun setDebateRounds(rounds: Int) {
        debateRounds.value = rounds.coerceIn(1, 10)
    }

    fun validationError(): String? {
        if (name.value.isBlank()) return "请输入群组名称"
        if (members.value.isEmpty()) return "请至少选择一个成员"
        if (mode.value == GroupMode.ORCHESTRATOR_WORKER && orchestratorId.value == null) {
            return "编排器模式必须指定一个主编排器成员"
        }
        return null
    }

    suspend fun save(): Boolean {
        val error = validationError()
        if (error != null) return false
        repository.save(
            Group(
                id = id ?: Uuid.random().toString(),
                name = name.value,
                mode = mode.value,
                members = members.value,
                orchestratorId = orchestratorId.value,
                debateRounds = debateRounds.value,
            )
        )
        saved.value = true
        return true
    }
}
