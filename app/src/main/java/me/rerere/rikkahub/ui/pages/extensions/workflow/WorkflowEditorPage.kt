package me.rerere.rikkahub.ui.pages.extensions.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AlignLeft
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.CommandLine
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Drag01
import me.rerere.hugeicons.stroke.Globe
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.data.ai.workflow.RunProgress
import me.rerere.rikkahub.data.ai.workflow.StepStatus
import me.rerere.rikkahub.data.model.AiStepConfig
import me.rerere.rikkahub.data.model.DelayStepConfig
import me.rerere.rikkahub.data.model.HttpStepConfig
import me.rerere.rikkahub.data.model.ShellStepConfig
import me.rerere.rikkahub.data.model.StepConfig
import me.rerere.rikkahub.data.model.StepType
import me.rerere.rikkahub.data.model.TextStepConfig
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun WorkflowEditorPage(
    id: String,
    vm: WorkflowEditorVM = koinViewModel(parameters = { parametersOf(id) }),
) {
    val workflow by vm.workflow.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val runProgress by vm.runProgress.collectAsStateWithLifecycle()
    val runSucceeded by vm.runSucceeded.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddTypeMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(workflow?.name ?: "工作流") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.run() }, enabled = !running && workflow?.steps?.isNotEmpty() == true) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(HugeIcons.Play, contentDescription = "运行")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        workflow?.let { current ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                WorkflowInfoFields(
                    workflow = current,
                    onNameChange = vm::updateName,
                    onDescriptionChange = vm::updateDescription,
                )

                if (current.steps.isEmpty()) {
                    EmptyEditorState(onAddStep = { showAddTypeMenu = true })
                } else {
                    StepFlowList(
                        steps = current.steps,
                        running = running,
                        runProgress = runProgress,
                        onMove = vm::moveStep,
                        onUpdateStep = vm::updateStep,
                        onRemoveStep = vm::removeStep,
                        onAddStep = { showAddTypeMenu = true },
                    )
                }

                if (runSucceeded != null) {
                    RunSummaryBar(
                        succeeded = runSucceeded == true,
                        progress = runProgress,
                        running = running,
                        onDismiss = vm::clearRunResult,
                    )
                }
            }
        }
    }

    if (showAddTypeMenu) {
        AddStepDialog(
            onDismiss = { showAddTypeMenu = false },
            onSelect = { type ->
                showAddTypeMenu = false
                vm.addStep(type)
            },
        )
    }
}

@Composable
private fun WorkflowInfoFields(
    workflow: Workflow,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = workflow.name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("名称") },
            singleLine = true,
        )
        OutlinedTextField(
            value = workflow.description,
            onValueChange = onDescriptionChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("描述（可选）") },
            singleLine = true,
        )
    }
}

@Composable
private fun EmptyEditorState(onAddStep: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "工作流还没有步骤",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAddStep) {
            Icon(HugeIcons.Add01, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("添加步骤")
        }
    }
}

@Composable
private fun StepFlowList(
    steps: List<WorkflowStep>,
    running: Boolean,
    runProgress: List<RunProgress>,
    onMove: (Int, Int) -> Unit,
    onUpdateStep: (String, String, StepConfig) -> Unit,
    onRemoveStep: (String) -> Unit,
    onAddStep: () -> Unit,
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onMove(from.index, to.index)
    }
    val progressByStep = runProgress.associateBy { it.stepId }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(steps, key = { it.id }) { step ->
            ReorderableItem(
                state = reorderableState,
                key = step.id,
            ) {
                val index = steps.indexOfFirst { it.id == step.id }
                StepNode(
                    modifier = Modifier.longPressDraggableHandle(),
                    step = step,
                    index = index,
                    isFirst = index == 0,
                    isLast = index == steps.size - 1,
                    progress = progressByStep[step.id],
                    running = running,
                    onUpdate = { name, config -> onUpdateStep(step.id, name, config) },
                    onRemove = { onRemoveStep(step.id) },
                )
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onAddStep) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("添加步骤")
                }
            }
        }
    }
}

@Composable
private fun StepNode(
    modifier: Modifier = Modifier,
    step: WorkflowStep,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    progress: RunProgress?,
    running: Boolean,
    onUpdate: (String, StepConfig) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by rememberSaveable(step.id) { mutableStateOf(false) }
    val status = progress?.status

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 上一个节点到本节点的连接线 + 箭头
        if (!isFirst) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(12.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Icon(
                imageVector = HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier),
            colors = CustomColors.cardColorsOnSurfaceContainer,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = stepTypeIcon(step.type),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = step.name,
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = stepTypeLabel(step.type),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 运行状态指示
                    when (status) {
                        StepStatus.RUNNING -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        StepStatus.SUCCESS -> Icon(
                            imageVector = HugeIcons.CheckmarkCircle02,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        StepStatus.FAILED -> Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        else -> {
                            Icon(
                                imageVector = HugeIcons.Drag01,
                                contentDescription = "长按拖动排序",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                if (expanded) {
                    StepConfigEditor(
                        step = step,
                        onUpdate = onUpdate,
                        onRemove = onRemove,
                    )
                }

                // 步骤输出（运行结束后展示）
                if (status != null && status != StepStatus.RUNNING) {
                    val output = progress?.output.orEmpty()
                    if (output.isNotBlank()) {
                        Text(
                            text = output.take(400),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = if (status == StepStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepConfigEditor(
    step: WorkflowStep,
    onUpdate: (String, StepConfig) -> Unit,
    onRemove: () -> Unit,
) {
    var name by rememberSaveable(step.id, step.name) { mutableStateOf(step.name) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                onUpdate(it, step.config)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("步骤名称") },
            singleLine = true,
        )

        when (val config = step.config) {
            is TextStepConfig -> TextConfigEditor(config, name) { newConfig ->
                onUpdate(name, newConfig)
            }
            is AiStepConfig -> AiConfigEditor(config, name) { newConfig ->
                onUpdate(name, newConfig)
            }
            is ShellStepConfig -> ShellConfigEditor(config, name) { newConfig ->
                onUpdate(name, newConfig)
            }
            is HttpStepConfig -> HttpConfigEditor(config, name) { newConfig ->
                onUpdate(name, newConfig)
            }
            is DelayStepConfig -> DelayConfigEditor(config, name) { newConfig ->
                onUpdate(name, newConfig)
            }
        }

        TextButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(
                imageVector = HugeIcons.Delete01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(4.dp))
            Text("删除步骤", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TextConfigEditor(
    config: TextStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var content by rememberSaveable(name, config.content) { mutableStateOf(config.content) }
    OutlinedTextField(
        value = content,
        onValueChange = {
            content = it
            onChange(config.copy(content = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("输出内容") },
        minLines = 2,
    )
}

@Composable
private fun AiConfigEditor(
    config: AiStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var prompt by rememberSaveable(name, config.prompt) { mutableStateOf(config.prompt) }
    OutlinedTextField(
        value = prompt,
        onValueChange = {
            prompt = it
            onChange(config.copy(prompt = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("提示词") },
        supportingText = { Text("支持 {{step.N.output}} 引用前序步骤输出，{{input.NAME}} 引用运行参数") },
        minLines = 3,
    )
}

@Composable
private fun ShellConfigEditor(
    config: ShellStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var command by rememberSaveable(name, config.command) { mutableStateOf(config.command) }
    OutlinedTextField(
        value = command,
        onValueChange = {
            command = it
            onChange(config.copy(command = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("命令") },
        minLines = 2,
    )
}

@Composable
private fun HttpConfigEditor(
    config: HttpStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var method by rememberSaveable(name, config.method) { mutableStateOf(config.method) }
    var url by rememberSaveable(name, config.url) { mutableStateOf(config.url) }
    var body by rememberSaveable(name, config.body) { mutableStateOf(config.body) }
    var headersText by rememberSaveable(name, config.headers.toHeaderText()) {
        mutableStateOf(config.headers.toHeaderText())
    }

    OutlinedTextField(
        value = url,
        onValueChange = {
            url = it
            onChange(config.copy(url = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("URL") },
        singleLine = true,
    )
    OutlinedTextField(
        value = method,
        onValueChange = {
            method = it
            onChange(config.copy(method = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("方法 (GET/POST/PUT/DELETE)") },
        singleLine = true,
    )
    OutlinedTextField(
        value = headersText,
        onValueChange = {
            headersText = it
            onChange(config.copy(headers = it.parseHeaders()))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("请求头（每行一个 Key: Value）") },
        minLines = 2,
    )
    OutlinedTextField(
        value = body,
        onValueChange = {
            body = it
            onChange(config.copy(body = it))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("请求体（可选）") },
        minLines = 2,
    )
}

@Composable
private fun DelayConfigEditor(
    config: DelayStepConfig,
    name: String,
    onChange: (StepConfig) -> Unit,
) {
    var seconds by rememberSaveable(name, config.seconds) { mutableStateOf(config.seconds.toString()) }
    OutlinedTextField(
        value = seconds,
        onValueChange = {
            seconds = it
            onChange(config.copy(seconds = it.toIntOrNull() ?: 1))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("等待秒数") },
        singleLine = true,
    )
}

@Composable
private fun AddStepDialog(
    onDismiss: () -> Unit,
    onSelect: (StepType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加步骤") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StepTypeRow(StepType.TEXT, "文本", "输出固定内容", { onSelect(StepType.TEXT) })
                StepTypeRow(StepType.AI, "AI 生成", "调用模型生成文本", { onSelect(StepType.AI) })
                StepTypeRow(StepType.SHELL, "命令", "执行 shell 命令", { onSelect(StepType.SHELL) })
                StepTypeRow(StepType.HTTP, "HTTP 请求", "发送网络请求", { onSelect(StepType.HTTP) })
                StepTypeRow(StepType.DELAY, "延迟", "等待指定时间", { onSelect(StepType.DELAY) })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun StepTypeRow(
    type: StepType,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = stepTypeIcon(type),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RunSummaryBar(
    succeeded: Boolean,
    progress: List<RunProgress>,
    running: Boolean,
    onDismiss: () -> Unit,
) {
    val statusText = when {
        running -> "运行中..."
        succeeded -> "运行完成"
        else -> "运行失败，已终止"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (running) MaterialTheme.colorScheme.surfaceVariant
                else if (succeeded) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "$statusText（成功 ${progress.count { it.status == StepStatus.SUCCESS }}/${progress.size}）",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.weight(1f))
        if (!running) {
            TextButton(onClick = onDismiss) {
                Text("清除")
            }
        }
    }
}

private fun stepTypeIcon(type: StepType): ImageVector = when (type) {
    StepType.TEXT -> HugeIcons.AlignLeft
    StepType.AI -> HugeIcons.Sparkles
    StepType.SHELL -> HugeIcons.CommandLine
    StepType.HTTP -> HugeIcons.Globe
    StepType.DELAY -> HugeIcons.Clock01
}

private fun stepTypeLabel(type: StepType): String = when (type) {
    StepType.TEXT -> "文本"
    StepType.AI -> "AI 生成"
    StepType.SHELL -> "命令"
    StepType.HTTP -> "HTTP 请求"
    StepType.DELAY -> "延迟"
}

private fun Map<String, String>.toHeaderText(): String =
    entries.joinToString("\n") { "${it.key}: ${it.value}" }

private fun String.parseHeaders(): Map<String, String> =
    lines()
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty()) key to value else null
            } else null
        }
        .toMap()
