package me.rerere.androidvm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Plus
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmInstance
import me.rerere.androidvm.VmVM
import me.rerere.androidvm.R
import me.rerere.androidvm.navigation.VmNavigator

/**
 * 仿光速虚拟机主界面：多实例列表。每个实例是独立、隔离的虚拟化空间。
 */
@Composable
fun VmListPage(vm: VmVM, navigator: VmNavigator) {
    val snackbar = remember { SnackbarHostState() }
    val progress by vm.progress.collectAsState()
    val installingId = vm.installingId.value
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.vm_list_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Lucide.Plus, contentDescription = null)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (vm.instances.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.vm_list_empty_hint), color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(vm.instances, key = { it.id }) { instance ->
                    val ratio = progress[instance.id] ?: 0f
                    val installing = installingId == instance.id
                    VmInstanceCard(
                        instance = instance,
                        installing = installing,
                        progress = ratio,
                        onOpen = { navigator.toDetail(instance.id) },
                        onReinstall = { vm.provision(instance) },
                        onDelete = { vm.delete(instance) },
                    )
            }
        }
    }

    if (showCreate) {
        VmCreateDialog(vm = vm, onDismiss = { showCreate = false })
    }
}
}

@Composable
private fun VmInstanceCard(
    instance: VmInstance,
    installing: Boolean,
    progress: Float,
    onOpen: () -> Unit,
    onReinstall: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon: ImageVector =
                    if (instance.engineType == VmEngineType.ANDROID) Lucide.Smartphone else Lucide.Server
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(instance.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        instance.systemLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = onReinstall) { Icon(Lucide.RefreshCw, null) }
                IconButton(onClick = onDelete) { Icon(Lucide.Trash2, null) }
            }
            if (installing) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}
