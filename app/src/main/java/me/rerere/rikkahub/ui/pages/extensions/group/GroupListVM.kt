package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.repository.GroupRepository

class GroupListVM(
    private val repository: GroupRepository,
) : ViewModel() {
    val groups: StateFlow<List<Group>> = repository.listGroups()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }
}
