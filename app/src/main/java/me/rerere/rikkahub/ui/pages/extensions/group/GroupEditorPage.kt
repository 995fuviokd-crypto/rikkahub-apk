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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.rikkahub.data.model.GroupMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.launch

@Composable
fun GroupEditorPage(
    id: String?,
    vm: GroupEditorVM = koinViewModel(parameters = { parametersOf(id ?: "") }),
) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    var search by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    val name by vm.name.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val orchestratorId by vm.orchestratorId.collectAsStateWithLifecycle()
    val debateRounds by vm.debateRounds.collectAsStateWithLifecycle()
    val reasoningLevel by vm.reasoningLevel.collectAsStateWithLifecycle()
    val enableTools by vm.enableTools.collectAsStateWithLifecycle()
    val workspaceId by vm.workspaceId.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
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
                Text("协作模式", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == GroupMode.DEBATE,
                        onClick = { vm.setMode(GroupMode.DEBATE) },
                        label = { Text("自由讨论") },
                    )
                    FilterChip(
                        selected = mode == GroupMode.ORCHESTRATOR_WORKER,
                        onClick = { vm.setMode(GroupMode.ORCHESTRATOR_WORKER) },
                        label = { Text("编排器-工作者") },
                    )
                    FilterChip(
                        selected = mode == GroupMode.PIPELINE,
                        onClick = { vm.setMode(GroupMode.PIPELINE) },
                        label = { Text("流水线") },
                    )
                }
            }

            if (mode == GroupMode.DEBATE) {
                item {
                    var roundsText by remember(debateRounds) { mutableStateOf(debateRounds.toString()) }
                    OutlinedTextField(
                        value = roundsText,
                        onValueChange = { value ->
                            roundsText = value
                            value.toIntOrNull()?.let { vm.setDebateRounds(it) }
                        },
                        label = { Text("讨论轮次") },
                        supportingText = {
                            Text(
                                text = "全体成员按此轮次轮流发言，结束后生成结论（1-10）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }
            }

            item {
                Text("思考过程", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "开启后成员回复会附带思考过程，并在消息中折叠展示",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    me.rerere.ai.core.ReasoningLevel.entries
                        .filter { it != me.rerere.ai.core.ReasoningLevel.MAX && it != me.rerere.ai.core.ReasoningLevel.XHIGH }
                        .forEach { level ->
                            FilterChip(
                                selected = reasoningLevel == level,
                                onClick = { vm.setReasoningLevel(level) },
                                label = { Text(level.label()) },
                            )
                        }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用工具与工作区", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "成员可调用工具、读取文件、执行命令（需绑定工作区）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = enableTools,
                        onCheckedChange = { vm.setEnableTools(it) },
                    )
                }
            }

            if (enableTools) {
                item {
                    Text("绑定工作区", style = MaterialTheme.typography.titleSmall)
                    if (workspaces.isEmpty()) {
                        Text(
                            text = "没有可用的工作区（Rootfs 未就绪），请先在「工作区」中创建并等待就绪",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = workspaceId == null,
                                onClick = { vm.setWorkspace(null) },
                                label = { Text("不绑定") },
                            )
                            workspaces.forEach { ws ->
                                FilterChip(
                                    selected = workspaceId == ws.id,
                                    onClick = { vm.setWorkspace(ws.id) },
                                    label = { Text(ws.name.ifBlank { ws.root }) },
                                )
                            }
                        }
                    }
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
                    mode = mode,
                    orchestratorId = orchestratorId,
                    onToggle = { vm.toggleMember(info) },
                    onRoleChange = { role ->
                        members.find { it.modelId == info.model.id }?.let { vm.setRole(it.id, role) }
                    },
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
    return androidx.compose.runtime.remember(models, search) {
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

private fun me.rerere.ai.core.ReasoningLevel.label(): String = when (this) {
    me.rerere.ai.core.ReasoningLevel.OFF -> "关闭"
    me.rerere.ai.core.ReasoningLevel.AUTO -> "自动"
    me.rerere.ai.core.ReasoningLevel.LOW -> "低"
    me.rerere.ai.core.ReasoningLevel.MEDIUM -> "中"
    me.rerere.ai.core.ReasoningLevel.HIGH -> "高"
    me.rerere.ai.core.ReasoningLevel.XHIGH -> "超高"
    me.rerere.ai.core.ReasoningLevel.MAX -> "最高"
}

@Composable
private fun ModelMemberRow(
    info: ModelInfo,
    selected: Boolean,
    member: me.rerere.rikkahub.data.model.GroupMember?,
    mode: GroupMode,
    orchestratorId: String?,
    onToggle: () -> Unit,
    onRoleChange: (String) -> Unit,
    onSetOrchestrator: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (mode == GroupMode.ORCHESTRATOR_WORKER) {
                    RadioButton(
                        selected = member != null && member.id == orchestratorId,
                        onClick = { if (member != null) onSetOrchestrator() },
                    )
                }
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
                if (mode == GroupMode.ORCHESTRATOR_WORKER && member != null && member.id == orchestratorId) {
                    Text(
                        text = "主编排器",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (selected && member != null) {
                OutlinedTextField(
                    value = member.role,
                    onValueChange = onRoleChange,
                    label = { Text("角色/职责描述") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        }
    }
}
