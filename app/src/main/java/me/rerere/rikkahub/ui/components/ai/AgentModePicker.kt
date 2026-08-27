package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.AgentMode
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.agentMode
import me.rerere.ai.provider.withAgentMode
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.SlidersHorizontal
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.agent.AcpRuntime
import me.rerere.rikkahub.ui.components.ui.ToggleSurface

@Composable
fun AgentModeButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    model: Model,
    onUpdateModel: (Model) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val currentMode = model.agentMode()

    if (showPicker) {
        AgentModePicker(
            currentMode = currentMode,
            onDismissRequest = { showPicker = false },
            onSelect = { mode ->
                showPicker = false
                onUpdateModel(model.withAgentMode(mode))
            }
        )
    }

    ToggleSurface(
        checked = currentMode != null,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                vertical = if (onlyIcon) 3.dp else 8.dp,
                horizontal = if (onlyIcon) 3.dp else 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.MagicWand01,
                    contentDescription = stringResource(R.string.agent_mode_picker_title),
                )
            }
            if (!onlyIcon) {
                Text(currentMode?.label() ?: stringResource(R.string.agent_mode_picker_title))
            }
        }
    }
}

@Composable
fun AgentModePicker(
    currentMode: AgentMode?,
    onDismissRequest: () -> Unit = {},
    onSelect: (AgentMode) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.agent_mode_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.agent_mode_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentMode.entries.forEach { mode ->
                    AgentModeOption(
                        mode = mode,
                        selected = mode == currentMode,
                        onClick = { onSelect(mode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentModeOption(
    mode: AgentMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ToggleSurface(
        checked = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = mode.label(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = HugeIcons.MagicWand01,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun AgentMode.label(): String = when (this) {
    AgentMode.STANDARD -> stringResource(R.string.agent_mode_standard)
    AgentMode.CODE -> stringResource(R.string.agent_mode_code)
    AgentMode.MINIMAL -> stringResource(R.string.agent_mode_minimal)
    AgentMode.CORDIS -> stringResource(R.string.agent_mode_cordis)
}

/**
 * ACP session-mode switcher (desktop-style runtime modes like plan / acceptEdits).
 * Hidden when the bound agent advertises no session modes.
 */
@Composable
fun AgentSessionModeButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    modes: List<AcpRuntime.SessionModeInfo>,
    currentModeId: String?,
    onSelect: (String) -> Unit,
) {
    if (modes.isEmpty()) return
    var showPicker by remember { mutableStateOf(false) }
    val currentName = modes.firstOrNull { it.id == currentModeId }?.name

    if (showPicker) {
        AgentSessionModePicker(
            modes = modes,
            currentModeId = currentModeId,
            onDismissRequest = { showPicker = false },
            onSelect = { modeId ->
                showPicker = false
                onSelect(modeId)
            },
        )
    }

    ToggleSurface(
        checked = currentModeId != null && currentName != modes.firstOrNull()?.name,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                vertical = if (onlyIcon) 3.dp else 8.dp,
                horizontal = if (onlyIcon) 3.dp else 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.SlidersHorizontal,
                    contentDescription = null,
                )
            }
            if (!onlyIcon) {
                Text(currentName ?: modes.firstOrNull()?.name ?: "")
            }
        }
    }
}

@Composable
private fun AgentSessionModePicker(
    modes: List<AcpRuntime.SessionModeInfo>,
    currentModeId: String?,
    onDismissRequest: () -> Unit = {},
    onSelect: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_page_session_mode_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.chat_page_session_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                modes.forEach { mode ->
                    ToggleSurface(
                        checked = mode.id == currentModeId,
                        onClick = { onSelect(mode.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = mode.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (mode.id == currentModeId) {
                                Icon(
                                    imageVector = HugeIcons.MagicWand01,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
