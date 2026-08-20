package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.pages.chat.StewardModeController
import me.rerere.rikkahub.ui.pages.chat.StewardModeStatus
import kotlin.math.roundToInt

@Composable
fun StewardModeButton(
    enabled: Boolean,
    status: StewardModeStatus,
    maxLoops: Int,
    unlimitedLoops: Boolean,
    onMaxLoopsChange: (Int) -> Unit,
    onUnlimitedLoopsChange: (Boolean) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }

    if (showSheet) {
        StewardModeSheet(
            enabled = enabled,
            status = status,
            maxLoops = maxLoops,
            unlimitedLoops = unlimitedLoops,
            onMaxLoopsChange = onMaxLoopsChange,
            onUnlimitedLoopsChange = onUnlimitedLoopsChange,
            onToggle = {
                onToggle()
                showSheet = false
            },
            onDismissRequest = { showSheet = false },
        )
    }

    ToggleSurface(
        checked = enabled,
        onClick = { showSheet = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 3.dp, horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.AiBrain01,
                    contentDescription = stringResource(R.string.steward_mode_title),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun StewardModeSheet(
    enabled: Boolean,
    status: StewardModeStatus,
    maxLoops: Int,
    unlimitedLoops: Boolean,
    onMaxLoopsChange: (Int) -> Unit,
    onUnlimitedLoopsChange: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
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
                    text = stringResource(R.string.steward_mode_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.steward_mode_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (enabled) {
                Text(
                    text = stringResource(stewardStatusLabel(status)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Button(onClick = onToggle) {
                Text(
                    text = stringResource(
                        if (enabled) R.string.steward_mode_stop else R.string.steward_mode_start
                    )
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.steward_mode_unlimited_loops),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.steward_mode_unlimited_loops_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = unlimitedLoops,
                        onCheckedChange = onUnlimitedLoopsChange,
                    )
                }
                if (unlimitedLoops) {
                    Text(
                        text = stringResource(R.string.steward_mode_unlimited_loops_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.steward_mode_max_loops, maxLoops),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = maxLoops.toFloat(),
                        onValueChange = { onMaxLoopsChange(it.roundToInt()) },
                        valueRange = StewardModeController.MIN_MAX_LOOPS.toFloat()..
                            StewardModeController.MAX_MAX_LOOPS.toFloat(),
                        steps = StewardModeController.MAX_MAX_LOOPS - StewardModeController.MIN_MAX_LOOPS - 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun stewardStatusLabel(status: StewardModeStatus): Int {
    return when (status) {
        StewardModeStatus.Idle -> R.string.steward_mode_status_idle
        StewardModeStatus.Monitoring -> R.string.steward_mode_status_monitoring
        StewardModeStatus.Judging -> R.string.steward_mode_status_judging
        StewardModeStatus.AutoSending -> R.string.steward_mode_status_auto_sending
        StewardModeStatus.Completed -> R.string.steward_mode_status_completed
        StewardModeStatus.Stopped -> R.string.steward_mode_status_stopped
    }
}
