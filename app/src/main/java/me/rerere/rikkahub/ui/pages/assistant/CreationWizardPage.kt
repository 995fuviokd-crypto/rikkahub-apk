package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.OutputStyle
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

data class ExampleDialog(
    val charMessage: String = "",
    val userMessage: String = "",
)

data class WizardState(
    val name: String = "",
    val greeting: String = "",
    val description: String = "",
    val selectedTemplate: String = "",
    val exampleDialogs: List<ExampleDialog> = emptyList(),
    val selectedPreset: String = "",
)

private val TEMPLATE_PRESETS = listOf(
    "General Chat" to "A friendly conversational assistant",
    "Learning Companion" to "A tutor that explains concepts and asks questions",
    "Programming Assistant" to "A coding expert that helps with software development",
    "Translator" to "A language expert that translates between languages",
    "Creative Writer" to "A creative writing partner for stories and content",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationWizardPage(assistantId: String?) {
    val vm: AssistantVM = koinViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var currentStep by remember { mutableIntStateOf(0) }
    var wizardState by remember { mutableStateOf(WizardState()) }

    val title = when (currentStep) {
        0 -> "Basic Info"
        1 -> "Character Background"
        2 -> "Example Dialogs"
        3 -> "Preset Template"
        else -> "Create Assistant"
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (currentStep) {
                0 -> StepBasicInfo(
                    state = wizardState,
                    onUpdate = { wizardState = it },
                )
                1 -> StepCharacterBackground(
                    state = wizardState,
                    onUpdate = { wizardState = it },
                )
                2 -> StepExampleDialogs(
                    state = wizardState,
                    onUpdate = { wizardState = it },
                )
                3 -> StepPresetTemplate(
                    state = wizardState,
                    onUpdate = { wizardState = it },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (currentStep > 0) {
                    TextButton(onClick = { currentStep-- }) {
                        Icon(HugeIcons.ArrowLeft01, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Back")
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                if (currentStep < 3) {
                    Button(
                        onClick = { currentStep++ },
                        enabled = when (currentStep) {
                            0 -> wizardState.name.isNotBlank()
                            else -> true
                        },
                    ) {
                        Text("Next")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(HugeIcons.ArrowRight01, null)
                    }
                } else {
                    Button(
                        onClick = {
                            vm.createAssistantFromWizard(wizardState)
                        },
                        enabled = wizardState.name.isNotBlank(),
                    ) {
                        Text("Create Assistant")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBasicInfo(
    state: WizardState,
    onUpdate: (WizardState) -> Unit,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Step 1: Basic Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = { onUpdate(state.copy(name = it)) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.greeting,
                onValueChange = { onUpdate(state.copy(greeting = it)) },
                label = { Text("Greeting") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun StepCharacterBackground(
    state: WizardState,
    onUpdate: (WizardState) -> Unit,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Step 2: Character Background",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = { onUpdate(state.copy(description = it)) },
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
            )
            Text(
                "Quick Templates",
                style = MaterialTheme.typography.titleSmall,
            )
            TEMPLATE_PRESETS.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { (name, desc) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (state.selectedTemplate == name)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp),
                            onClick = { onUpdate(state.copy(selectedTemplate = name)) },
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepExampleDialogs(
    state: WizardState,
    onUpdate: (WizardState) -> Unit,
) {
    val dialogs = remember(state) { state.exampleDialogs.toMutableList() }

    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Step 3: Example Dialogs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Add at least 1 example dialog to help define the assistant's style",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            dialogs.forEachIndexed { index, dialog ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Example ${index + 1}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(
                                onClick = {
                                    dialogs.removeAt(index)
                                    onUpdate(state.copy(exampleDialogs = dialogs.toList()))
                                },
                            ) {
                                Icon(HugeIcons.Delete01, "Remove")
                            }
                        }
                        OutlinedTextField(
                            value = dialog.charMessage,
                            onValueChange = {
                                dialogs[index] = dialog.copy(charMessage = it)
                                onUpdate(state.copy(exampleDialogs = dialogs.toList()))
                            },
                            label = { Text("Assistant says") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 3,
                        )
                        OutlinedTextField(
                            value = dialog.userMessage,
                            onValueChange = {
                                dialogs[index] = dialog.copy(userMessage = it)
                                onUpdate(state.copy(exampleDialogs = dialogs.toList()))
                            },
                            label = { Text("User says") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 3,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    dialogs.add(ExampleDialog())
                    onUpdate(state.copy(exampleDialogs = dialogs.toList()))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Add01, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Example Dialog")
            }
        }
    }
}

@Composable
private fun StepPresetTemplate(
    state: WizardState,
    onUpdate: (WizardState) -> Unit,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Step 4: Preset Template",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Choose a starting template or create from scratch",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TEMPLATE_PRESETS.forEach { (name, desc) ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (state.selectedPreset == name)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(2.dp),
                    onClick = { onUpdate(state.copy(selectedPreset = name)) },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.selectedPreset == name) {
                            Text(
                                "✓",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
