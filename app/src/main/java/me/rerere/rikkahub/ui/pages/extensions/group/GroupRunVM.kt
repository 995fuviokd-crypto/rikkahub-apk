package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupRun
import me.rerere.rikkahub.data.repository.GroupRepository

class GroupRunVM(
    private val runId: String,
    private val repository: GroupRepository,
) : ViewModel() {
    val run: StateFlow<GroupRun?> = repository.getRun(runId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val messages: StateFlow<List<GroupMessage>> = repository.listMessages(runId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val groupName = MutableStateFlow("")

    init {
        viewModelScope.launch {
            run.collect { r ->
                if (r != null) {
                    groupName.value = repository.getGroupById(r.groupId)?.name ?: ""
                }
            }
        }
    }
}
