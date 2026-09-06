package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import me.rerere.rikkahub.data.model.OutputStyle
import me.rerere.rikkahub.data.model.OutputStyleFrontmatter
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun OutputStyleEditorPage(styleId: String?, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val existingStyle = remember(styleId, settings.outputStyles) {
        settings.outputStyles.firstOrNull { it.id.toString() == styleId }
    }

    val name = remember { mutableStateOf(existingStyle?.name ?: "") }
    val description = remember { mutableStateOf(existingStyle?.description ?: "") }
    val instructions = remember { mutableStateOf(existingStyle?.instructions ?: "") }
    var keepDefault by remember { mutableStateOf(existingStyle?.frontmatter?.keepDefaultInstructions ?: true) }

    val canSave = name.value.isNotBlank() && instructions.value.isNotBlank()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (existingStyle != null) "Edit Output Style" else "New Output Style") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(
                        onClick = {
                            val style = OutputStyle(
                                id = existingStyle?.id ?: Uuid.random(),
                                name = name.value,
                                description = description.value,
                                frontmatter = OutputStyleFrontmatter(keepDefaultInstructions = keepDefault),
                                instructions = instructions.value,
                                builtin = false,
                            )
                            val newStyles = if (existingStyle != null) {
                                settings.outputStyles.map { if (it.id == style.id) style else it }
                            } else {
                                settings.outputStyles + style
                            }
                            vm.updateSettings(settings.copy(outputStyles = newStyles))
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
                value = name.value,
                onValueChange = { name.value = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = description.value,
                onValueChange = { description.value = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = instructions.value,
                onValueChange = { instructions.value = it },
                label = { Text("Instructions") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Keep Default Instructions", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Append to existing system prompt instead of replacing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepDefault, onCheckedChange = { keepDefault = it })
            }
        }
    }
}
