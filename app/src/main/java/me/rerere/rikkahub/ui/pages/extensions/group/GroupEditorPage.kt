package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun GroupEditorPage(
    id: String?,
    vm: GroupEditorVM = koinViewModel(parameters = { parametersOf(id ?: "") }),
) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var search by remember { mutableStateOf("") }

    val name by vm.name.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val orchestratorId by vm.orchestratorId.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    val filtered = rememberFiltered(models, search)

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (id == null) "新建群组" else "编辑群组") },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(
                        onClick = {
                            val check = vm.validationError()
                            if (check != null) {
                                scope.launch { snackbarHostState.showSnackbar(check) }
                            } else {
                                scope.launch {
                                    if (vm.save()) navController.popBackStack()
                                }
                            }
                        }
                    ) {
                        Text("保存")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (loading) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { vm.name.value = it },
                    label = { Text("群组名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("选择成员", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "勾选参与协作的模型成员，并用单选指定其中一个为「主编排器」（负责拆解任务、分派与汇总）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索模型") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(),
                )
            }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = if (models.isEmpty()) "未找到可用模型，请先在「设置 → 模型管理」中配置" else "没有匹配的模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(filtered, key = { it.model.id.toString() }) { info ->
                ModelMemberRow(
                    info = info,
                    selected = vm.isMemberSelected(info.model.id),
                    member = members.find { it.modelId == info.model.id },
                    isOrchestrator = members.find { it.modelId == info.model.id }?.id == orchestratorId,
                    onToggle = { vm.toggleMember(info) },
                    onSetOrchestrator = {
                        members.find { it.modelId == info.model.id }?.let { vm.setOrchestrator(it.id) }
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberFiltered(
    models: List<ModelInfo>,
    search: String,
): List<ModelInfo> {
    return remember(models, search) {
        if (search.isBlank()) {
            models
        } else {
            val keyword = search.trim()
            models.filter { info ->
                info.model.displayName.contains(keyword, ignoreCase = true) ||
                    info.model.modelId.contains(keyword, ignoreCase = true) ||
                    info.providerName.contains(keyword, ignoreCase = true)
            }
        }
    }
}

@Composable
private fun ModelMemberRow(
    info: ModelInfo,
    selected: Boolean,
    member: me.rerere.rikkahub.data.model.GroupMember?,
    isOrchestrator: Boolean,
    onToggle: () -> Unit,
    onSetOrchestrator: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isOrchestrator,
                enabled = selected,
                onClick = { if (member != null) onSetOrchestrator() },
            )
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.model.displayName.ifBlank { info.model.modelId },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = info.providerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isOrchestrator) {
                Text(
                    text = "主编排器",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
