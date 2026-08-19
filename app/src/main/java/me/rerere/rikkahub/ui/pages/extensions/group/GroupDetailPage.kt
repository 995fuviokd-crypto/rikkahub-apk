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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.GroupMessage
import me.rerere.rikkahub.data.model.GroupRun
import me.rerere.rikkahub.data.model.MessageKind
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun GroupDetailPage(
    id: String,
    vm: GroupDetailVM = koinViewModel(parameters = { parametersOf(id) }),
) {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val group by vm.group.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val inlineMessages by vm.inlineMessages.collectAsStateWithLifecycle()
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
                            vm.launchRun(mission)
                            mission = ""
                        },
                        onStop = { vm.stopRun() },
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("消息展示", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (inlineMessages) "在详情页内联展示消息" else "消息展示在独立页面",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = inlineMessages,
                            onCheckedChange = { vm.setInlineMessages(it) },
                        )
                    }
                }

                item {
                    Text("运行历史", style = MaterialTheme.typography.titleSmall)
                }

                if (runs.isEmpty()) {
                    item {
                        Text(
                            text = "还没有运行记录，发布一条指令开始协作",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(runs, key = { it.id }) { run ->
                    RunCard(
                        run = run,
                        selected = run.id == selectedRunId,
                        onClick = {
                            if (inlineMessages) {
                                vm.selectRun(run.id)
                            } else {
                                navController.navigate(Screen.GroupRun(run.id))
                            }
                        },
                    )
                }

                if (inlineMessages) {
                    item {
                        Text(
                            text = if (selectedRunId == null) "消息时间线" else "消息时间线 · ${runs.find { it.id == selectedRunId }?.mission ?: ""}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    when {
                        selectedRunId == null -> {
                            item {
                                Text(
                                    text = "发布一条指令，群组协作的过程与讨论会显示在这里",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        running && messages.none { it.kind != MessageKind.SYSTEM } -> {
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
}

@Composable
private fun GroupInfoCard(group: me.rerere.rikkahub.data.model.Group) {
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
                    text = group.mode.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "${group.members.size} 个成员",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                label = { Text("发布指令") },
                placeholder = { Text("例如：请群组一起调研并设计一个推荐系统方案") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLaunch,
                    enabled = !running && mission.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(if (running) "协作中" else "启动群组", modifier = Modifier.padding(start = 6.dp))
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

@Composable
private fun RunCard(
    run: GroupRun,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CustomColors.cardColorsOnSurfaceContainer
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = run.status.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = run.status.color(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(run.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = run.mission,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (run.summary.isNotBlank()) {
                Text(
                    text = run.summary.take(120),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
