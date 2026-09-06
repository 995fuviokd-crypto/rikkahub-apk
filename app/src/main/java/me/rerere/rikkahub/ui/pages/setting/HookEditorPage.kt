package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.model.HookConfig
import me.rerere.rikkahub.data.model.HookEvent
import me.rerere.rikkahub.data.model.HookProcessorType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun HookEditorPage(hookId: String?, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val existingHook = remember(hookId, settings.assistants) {
        settings.assistants.flatMap { it.hookConfigs }
            .firstOrNull { it.id.toString() == hookId }
    }

    var name by remember { mutableStateOf(existingHook?.name ?: "") }
    var command by remember { mutableStateOf(existingHook?.command ?: "") }
    var selectedEvent by remember { mutableStateOf(existingHook?.event ?: HookEvent.PRE_SEND) }
    var selectedProcessor by remember { mutableStateOf(existingHook?.processorType ?: HookProcessorType.SHELL) }
    var failSilently by remember { mutableStateOf(existingHook?.failSilently ?: true) }
    var timeoutText by remember { mutableStateOf((existingHook?.timeoutMs ?: 5000L).toString()) }

    val canSave = name.isNotBlank() && command.isNotBlank()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (existingHook != null) "Edit Hook" else "New Hook") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(
                        onClick = {
                            val timeoutMs = timeoutText.toLongOrNull() ?: 5000L
                            val hook = HookConfig(
                                id = existingHook?.id ?: Uuid.random(),
                                name = name,
                                enabled = existingHook?.enabled ?: true,
                                event = selectedEvent,
                                processorType = selectedProcessor,
                                command = command,
                                timeoutMs = timeoutMs,
                                failSilently = failSilently,
                            )
                            val currentAssistantId = settings.assistantId
                            val newAssistants = if (existingHook != null) {
                                settings.assistants.map { assistant ->
                                    assistant.copy(
                                        hookConfigs = assistant.hookConfigs.map {
                                            if (it.id == hook.id) hook else it
                                        }
                                    )
                                }
                            } else {
                                settings.assistants.map { assistant ->
                                    if (assistant.id == currentAssistantId) assistant.copy(hookConfigs = assistant.hookConfigs + hook)
                                    else assistant
                                }
                            }
                            vm.updateSettings(settings.copy(assistants = newAssistants))
                        },
                        enabled = canSave,
                    ) {
                        Text("Save")
                    }
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Event", style = MaterialTheme.typography.titleSmall)
                    Select(
                        options = HookEvent.entries,
                        selectedOption = selectedEvent,
                        onOptionSelected = { selectedEvent = it },
                        optionToString = @Composable { event ->
                            event.name.lowercase().replace("_", " ")
                        },
                    )
                }
            }
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Processor Type", style = MaterialTheme.typography.titleSmall)
                    Select(
                        options = HookProcessorType.entries,
                        selectedOption = selectedProcessor,
                        onOptionSelected = { selectedProcessor = it },
                        optionToString = @Composable { processorType ->
                            processorType.name.lowercase().replace("_", " ")
                        },
                    )
                }
            }
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = {
                    Text(
                        when (selectedProcessor) {
                            HookProcessorType.SHELL -> "Shell Command"
                            HookProcessorType.HTTP -> "URL"
                            HookProcessorType.LLM -> "LLM Prompt"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
            )
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { timeoutText = it },
                label = { Text("Timeout (ms)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Fail Silently", style = MaterialTheme.typography.titleSmall)
                Switch(checked = failSilently, onCheckedChange = { failSilently = it })
            }
        }
    }
}
