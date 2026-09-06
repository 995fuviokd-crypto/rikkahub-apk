package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Webhook
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.HookConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingHooksPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    var showDeleteDialog by remember { mutableStateOf<HookConfig?>(null) }

    val allHooks = remember(settings) {
        settings.assistants.flatMap { it.hookConfigs }
            .distinctBy { it.id }
            .sortedBy { it.name }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Hooks") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.HookEditor(null)) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(HugeIcons.Add01, null)
                        Text("Create New Hook", modifier = Modifier.weight(1f))
                        Icon(HugeIcons.ArrowRight01, null)
                    }
                }
            }

            items(allHooks) { hook ->
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate(Screen.HookEditor(hook.id.toString())) }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(HugeIcons.Webhook, null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                hook.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${hook.event.name} / ${hook.processorType.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = hook.enabled,
                            onCheckedChange = { enabled ->
                                updateHookInSettings(vm, settings, hook.copy(enabled = enabled))
                            },
                        )
                        IconButton(onClick = { showDeleteDialog = hook }) {
                            Icon(HugeIcons.Delete01, "Delete")
                        }
                    }
                }
            }
        }
    }

    showDeleteDialog?.let { hook ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Hook") },
            text = { Text("Delete '${hook.name}'? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteHookFromSettings(vm, settings, hook.id)
                    showDeleteDialog = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel") }
            },
        )
    }
}

private fun updateHookInSettings(vm: SettingVM, settings: me.rerere.rikkahub.data.datastore.Settings, updatedHook: HookConfig) {
    val newAssistants = settings.assistants.map { assistant ->
        assistant.copy(
            hookConfigs = assistant.hookConfigs.map { hook ->
                if (hook.id == updatedHook.id) updatedHook else hook
            }
        )
    }
    vm.updateSettings(settings.copy(assistants = newAssistants))
}

private fun deleteHookFromSettings(vm: SettingVM, settings: me.rerere.rikkahub.data.datastore.Settings, hookId: Uuid) {
    val newAssistants = settings.assistants.map { assistant ->
        assistant.copy(hookConfigs = assistant.hookConfigs.filter { it.id != hookId })
    }
    vm.updateSettings(settings.copy(assistants = newAssistants))
}
