package me.rerere.rikkahub.ui.pages.extensions.workflow

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FlowSquare
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.workflow.StepStatus
import me.rerere.rikkahub.data.ai.workflow.WorkflowRunResult
import me.rerere.rikkahub.data.db.entity.WorkflowEntity
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowListPage(vm: WorkflowListVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val workflows by vm.workflows.collectAsStateWithLifecycle()
    val runningId by vm.runningId.collectAsStateWithLifecycle()
    val runResult by vm.runResult.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var deleteTarget by remember { mutableStateOf<WorkflowEntity?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("工作流") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        vm.create()?.let { id ->
                            navController.navigate(Screen.WorkflowEditor(id))
                        }
                    }
                }
            ) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (workflows.isEmpty()) {
                item {
                    EmptyWorkflowState()
                }
            }

            items(workflows, key = { it.id }) { workflow ->
                WorkflowCard(
                    workflow = workflow,
                    running = runningId == workflow.id,
                    onClick = { navController.navigate(Screen.WorkflowEditor(workflow.id)) },
                    onRun = { vm.run(workflow.id) },
                    onDelete = { deleteTarget = workflow },
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = "删除工作流",
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = {
            deleteTarget?.let { vm.delete(it.id) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text("确定要删除该工作流吗？此操作不可恢复。")
    }

    runResult?.let { result ->
        RunResultDialog(
            result = result,
            onDismiss = { vm.clearRunResult() },
        )
    }
}

@Composable
private fun EmptyWorkflowState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.FlowSquare,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "还没有工作流",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "点击右下角按钮创建工作流，把多个步骤编排成自动化流程",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkflowCard(
    workflow: WorkflowEntity,
    running: Boolean,
    onClick: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val steps = remember(workflow.stepsJson) {
        runCatching {
            JsonInstant.decodeFromString<List<WorkflowStep>>(workflow.stepsJson)
        }.getOrDefault(emptyList())
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.FlowSquare,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = workflow.name,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append("${steps.size} 个步骤")
                            if (workflow.description.isNotBlank()) {
                                append(" · ${workflow.description}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("运行") },
                            leadingIcon = { Icon(HugeIcons.Play, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRun()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunResultDialog(
    result: WorkflowRunResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (result.succeeded) "运行完成" else "运行失败")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                result.steps.forEach { step ->
                    val statusText = when (step.status) {
                        StepStatus.SUCCESS -> "✓"
                        StepStatus.FAILED -> "✗"
                        else -> "·"
                    }
                    val color = when (step.status) {
                        StepStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                        StepStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "$statusText ${step.stepIndex + 1}. ${step.stepName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                        )
                        if (step.output.isNotBlank()) {
                            Text(
                                text = step.output.take(200),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}
