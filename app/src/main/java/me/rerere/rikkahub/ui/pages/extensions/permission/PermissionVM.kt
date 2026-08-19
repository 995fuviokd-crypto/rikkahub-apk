package me.rerere.rikkahub.ui.pages.extensions.permission

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.permission.AuditEntry
import me.rerere.rikkahub.data.permission.HeadlessController
import me.rerere.rikkahub.data.permission.PermissionLevel
import me.rerere.rikkahub.data.permission.PermissionManager
import me.rerere.rikkahub.data.permission.ShizukuApi
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.writeBooleanPreference

class PermissionVM(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val headlessController: HeadlessController,
) : ViewModel() {
    val level = MutableStateFlow(PermissionLevel.NONE)
    val accessibilityReady = MutableStateFlow(false)
    val adbReady = MutableStateFlow(false)
    val rootReady = MutableStateFlow(false)
    val shizukuReady = MutableStateFlow(false)
    val shizukuLoaded = MutableStateFlow(false)
    val checking = MutableStateFlow(false)
    val auditLogs = MutableStateFlow<List<AuditEntry>>(emptyList())
    val headlessEnabled = MutableStateFlow(false)
    val headlessSupport = MutableStateFlow("")

    init {
        headlessEnabled.value = context.readBooleanPreference("headless_enabled", false)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            checking.value = true
            accessibilityReady.value = permissionManager.accessibilityReady()
            rootReady.value = permissionManager.rootReady()
            shizukuLoaded.value = ShizukuApi.isLoaded()
            shizukuReady.value = permissionManager.shizukuReady()
            adbReady.value = permissionManager.adbReady()
            level.value = permissionManager.currentLevel()
            headlessSupport.value = headlessController.supportLevel()
            auditLogs.value = permissionManager.auditLogs()
            checking.value = false
        }
    }

    fun requestShizuku() {
        ShizukuApi.requestPermission()
    }

    fun setHeadless(enabled: Boolean) {
        headlessEnabled.value = enabled
        context.writeBooleanPreference("headless_enabled", enabled)
    }

    fun clearAudit() {
        permissionManager.clearAudit()
        auditLogs.value = emptyList()
    }
}
