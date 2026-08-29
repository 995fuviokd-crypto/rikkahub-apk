package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun GroupDetailPage(
    id: String,
    vm: GroupDetailVM = koinViewModel(parameters = { parametersOf(id) }),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val group by vm.group.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val selectedRunId by vm.selectedRunId.collectAsStateWithLifecycle()
    val launchError by vm.launchError.collectAsStateWithLifecycle()
    var mission by remember { mutableStateOf("") }

    LaunchedEffect(launchError) {
        launchError?.let { message ->
            snackbarHostState.showSnackbar(message)
            vm.consumeLaunchError()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(group?.name ?: "群组详情") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (group == null) {
                item {
                    Text(
                        text = "群组不存在或正在加载",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }
            group?.let { g ->
                item {
                    GroupInfoCard(g)
                }

                item {
                    MissionInputCard(
                        mission = mission,
                        onMissionChange = { mission = it },
                        running = running,
                        onLaunch = {
                            if (running) {
                                vm.appendInstruction(mission)
                            } else {
                                vm.launchRun(mission)
                            }
                            mission = ""
                        },
                        onStop = { vm.stopRun() },
                    )
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("协作过程", style = MaterialTheme.typography.titleSmall)
                        if (runs.isNotEmpty()) {
                            RunSelector(
                                runs = runs,
                                selectedRunId = selectedRunId,
                                onSelect = { vm.selectRun(it) },
                            )
                        }
                    }
                }
                when {
                    selectedRunId == null -> {
                        item {
                            Text(
                                text = "发布一条指令，群组协作的过程会显示在这里",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    running && messages.none { it.kind != MessageKind.SYSTEM && it.kind != MessageKind.USER } -> {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "正在等待 AI 成员响应…（超过 45 秒会提示超时）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    messages.isEmpty() -> {
                        item {
                            Text(
                                text = "该次运行暂无消息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        items(messages, key = { it.id }) { message ->
                            GroupMessageItem(message)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunSelector(
    runs: List<me.rerere.rikkahub.data.model.GroupRun>,
    selectedRunId: String?,
    onSelect: (String) -> Unit,
) {
    val selected = runs.find { it.id == selectedRunId }
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = selected?.status?.label() ?: "选择运行",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { expanded = true }
                .padding(4.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            runs.forEach { run ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(run.mission.take(24), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = run.status.label(),
                                style = MaterialTheme.typography.labelSmall,
                                color = run.status.color(),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(run.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupInfoCard(group: Group) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${group.members.size} 个成员",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val parsed = group.scheduleCron?.let { me.rerere.rikkahub.data.ai.group.GroupCron.parse(it) }
            if (group.scheduleCron?.isNotBlank() == true) {
                val desc = me.rerere.rikkahub.data.ai.group.GroupCron.describe(group.scheduleCron)
                val next = parsed?.let { me.rerere.rikkahub.data.ai.group.GroupCron.nextRun(it) }
                Text(
                    text = "定时任务：${desc ?: group.scheduleCron}" +
                        (next?.let { "  ·  下次运行 ${it.monthValue}月${it.dayOfMonth}日 ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                Text(
                    text = "未配置定时任务，仅手动运行",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            group.members.forEach { member ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (member.id == group.orchestratorId) {
                        Text(
                            text = "主导",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        text = member.role,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MissionInputCard(
    mission: String,
    onMissionChange: (String) -> Unit,
    running: Boolean,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = mission,
                onValueChange = onMissionChange,
                label = { Text(if (running) "追加指令" else "发布指令") },
                placeholder = { Text(if (running) "运行中可追加指令，将补充给后续成员" else "例如：请群组一起调研并设计一个推荐系统方案") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLaunch,
                    enabled = mission.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        if (running) "追加指令" else "启动群组",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (running) {
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = HugeIcons.Stop,
                            contentDescription = "停止",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun RunStatus.label(): String = when (this) {
    RunStatus.RUNNING -> "进行中"
    RunStatus.SUCCESS -> "成功"
    RunStatus.FAILED -> "失败"
    RunStatus.STOPPED -> "已停止"
}

private fun RunStatus.color(): androidx.compose.ui.graphics.Color = when (this) {
    RunStatus.RUNNING -> androidx.compose.ui.graphics.Color(0xFF2962FF)
    RunStatus.SUCCESS -> androidx.compose.ui.graphics.Color(0xFF00C853)
    RunStatus.FAILED -> androidx.compose.ui.graphics.Color(0xFFFF1744)
    RunStatus.STOPPED -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
}
