package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.files.PresetImportExportService
import me.rerere.rikkahub.data.model.ImportSummary
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ErrorCard
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPresetManagerPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val importExportService = koinInject<PresetImportExportService>()
    var importResult by remember { mutableStateOf<ImportSummary?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Preset Manager") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Import Preset Package", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Import a .zip preset package containing character cards, lorebooks, output styles, hooks, and more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    errorMessage = null
                                }
                            },
                        ) {
                            Text("Select .zip file")
                        }
                    }
                }
            }

            item {
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Export Assistant Preset", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Export the current assistant configuration as a .zip preset package.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val assistants = settings.assistants
                                        if (assistants.isEmpty()) {
                                            errorMessage = "No assistants to export"
                                            return@launch
                                        }
                                        val result = importExportService.exportPreset(assistants.first().id)
                                        result.onSuccess { bytes ->
                                            // In a real app, this would trigger a file save dialog
                                            // For now, just acknowledge success
                                            println("Exported ${bytes.size} bytes")
                                        }.onFailure { e ->
                                            errorMessage = "Export failed: ${e.message}"
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = "Export failed: ${e.message}"
                                    }
                                }
                            },
                        ) {
                            Text("Export First Assistant")
                        }
                    }
                }
            }

            importResult?.let { summary ->
                item {
                    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Import Result", style = MaterialTheme.typography.titleMedium)
                            Text("Character Card: ${if (summary.characterCardImported) "Yes" else "No"}")
                            Text("Lorebooks: ${summary.lorebooksImported}")
                            Text("Injections: ${summary.injectionsImported}")
                            Text("Output Styles: ${summary.outputStylesImported}")
                            Text("Hooks: ${summary.hooksImported}")
                            Text("Regex Scripts: ${summary.regexScriptsImported}")
                            if (summary.conflicts.isNotEmpty()) {
                                Text(
                                    "Conflicts: ${summary.conflicts.size}",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            errorMessage?.let { msg ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            msg,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}
