package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.HookTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputStyleTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

data class InjectionContribution(
    val name: String,
    val position: String,
    val priority: Int,
    val tokenEstimate: Int,
    val content: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetDebuggerPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val contributions = remember(assistant, settings) {
        computeInjectionContributions(assistant, settings)
    }
    val totalTokens = contributions.sumOf { it.tokenEstimate }
    val maxTokens = settings.assistants.firstOrNull { it.id == assistant.id }?.let { a ->
        ((a.maxTokens ?: 8000) * 0.15).toInt()
    } ?: 2000
    val warnings = mutableListOf<String>()
    if (totalTokens > maxTokens) {
        warnings.add("Total injection tokens ($totalTokens) exceed 15% of max tokens ($maxTokens)")
    }
    if (contributions.groupBy { it.position }.any { it.value.size > 3 }) {
        warnings.add("Multiple injections at same position may cause ordering conflicts")
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Preset Debugger") },
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
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Token Usage",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "$totalTokens / $maxTokens tokens",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (totalTokens > maxTokens)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurface,
                        )
                        if (warnings.isNotEmpty()) {
                            warnings.forEach { w ->
                                Text(
                                    w,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            items(contributions) { contribution ->
                InjectionContributionCard(contribution, maxTokens)
            }
        }
    }
}

@Composable
private fun InjectionContributionCard(
    contribution: InjectionContribution,
    maxTokens: Int,
) {
    var expanded by remember { mutableStateOf(false) }
    val isOverBudget = contribution.tokenEstimate > maxTokens / 4

    Card(
        colors = if (isOverBudget)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else
            CustomColors.cardColorsOnSurfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    contribution.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${contribution.position} | p${contribution.priority}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${contribution.tokenEstimate} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = if (isOverBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                ) {
                    Text(
                        contribution.content.take(500),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 20,
                    )
                }
            }
        }
    }
}

private fun computeInjectionContributions(
    assistant: Assistant,
    settings: me.rerere.rikkahub.data.datastore.Settings,
): List<InjectionContribution> {
    val contributions = mutableListOf<InjectionContribution>()

    settings.modeInjections.filter { it.id in assistant.modeInjectionIds }.forEach { injection ->
        contributions.add(
            InjectionContribution(
                name = injection.name,
                position = injection.position.name,
                priority = injection.priority,
                tokenEstimate = injection.content.length / 4,
                content = injection.content,
            )
        )
    }

    settings.lorebooks.filter { it.id in assistant.lorebookIds }.forEach { lorebook ->
        lorebook.entries.filter { it.enabled }.forEach { entry ->
            contributions.add(
                InjectionContribution(
                    name = "${lorebook.name} / ${entry.name}",
                    position = (entry.positionOverride ?: entry.position).name,
                    priority = entry.priority,
                    tokenEstimate = (entry.tokenBudget ?: (entry.content.length / 4)),
                    content = entry.content,
                )
            )
        }
    }

    assistant.hookConfigs.filter { it.enabled }.forEach { hook ->
        contributions.add(
            InjectionContribution(
                name = "Hook: ${hook.name}",
                position = "HOOK_${hook.event.name}",
                priority = 0,
                tokenEstimate = hook.command.length / 4,
                content = "${hook.processorType}: ${hook.command}",
            )
        )
    }

    assistant.activeOutputStyleId?.let { styleId ->
        settings.outputStyles.firstOrNull { it.id == styleId }?.let { style ->
            contributions.add(
                InjectionContribution(
                    name = "Output Style: ${style.name}",
                    position = "SYSTEM_OVERRIDE",
                    priority = 0,
                    tokenEstimate = style.instructions.length / 4,
                    content = if (style.frontmatter.keepDefaultInstructions)
                        "[Append] ${style.instructions}"
                    else
                        "[Replace] ${style.instructions}",
                )
            )
        }
    }

    return contributions.sortedByDescending { it.tokenEstimate }
}
