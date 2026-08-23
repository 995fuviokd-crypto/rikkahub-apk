package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.AgentPlatform
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.agent.AgentEnvStatus
import me.rerere.rikkahub.data.ai.agent.AgentInstallPhase
import me.rerere.rikkahub.data.ai.agent.AgentInstallProgress
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

private data class AgentDescriptor(
    val platform: AgentPlatform,
    val labelRes: Int,
    val descRes: Int,
)

private val agentDescriptors: List<AgentDescriptor> = listOf(
    AgentDescriptor(
        platform = AgentPlatform.GEMINI_CLI,
        labelRes = R.string.setting_provider_page_platform_agent_gemini_cli,
        descRes = R.string.setting_provider_page_platform_agent_gemini_cli_desc,
    ),
    AgentDescriptor(
        platform = AgentPlatform.CODEX,
        labelRes = R.string.setting_provider_page_platform_agent_codex,
        descRes = R.string.setting_provider_page_platform_agent_codex_desc,
    ),
    AgentDescriptor(
        platform = AgentPlatform.CLAUDE_CODE,
        labelRes = R.string.setting_provider_page_platform_agent_claude_code,
        descRes = R.string.setting_provider_page_platform_agent_claude_code_desc,
    ),
    AgentDescriptor(
        platform = AgentPlatform.ANTHROPIC_CLAUDE_CODE,
        labelRes = R.string.setting_provider_page_platform_agent_anthropic_claude_code,
        descRes = R.string.setting_provider_page_platform_agent_anthropic_claude_code_desc,
    ),
    AgentDescriptor(
        platform = AgentPlatform.OPENCODE,
        labelRes = R.string.setting_provider_page_platform_agent_opencode,
        descRes = R.string.setting_provider_page_platform_agent_opencode_desc,
    ),
    AgentDescriptor(
        platform = AgentPlatform.DEEPSEEK_HARNESS,
        labelRes = R.string.setting_provider_page_platform_agent_dsh,
        descRes = R.string.setting_provider_page_platform_agent_dsh_desc,
    ),
)

@Composable
fun SettingAgentPage(vm: SettingAgentVM = koinViewModel()) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val selectedWorkspaceId by vm.selectedWorkspaceId.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val installing by vm.installing.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    val selectedWorkspace = workspaces.firstOrNull { it.id == selectedWorkspaceId }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("Agent 模式管理")
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                WorkspaceSelectorCard(
                    workspace = selectedWorkspace,
                    hasWorkspaces = workspaces.isNotEmpty(),
                    onClick = { showWorkspacePicker = true },
                    onNavigateToCreate = { navController.navigate(Screen.Workspaces) },
                )
            }

            item {
                Text(
                    text = "在此安装 CLI 编码智能体，安装完成后可在「供应商」中绑定为 Agent 模式。安装目标为所选工作区的运行环境（Node.js + npm 会自动安装）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            items(agentDescriptors) { descriptor ->
                AgentItemCard(
                    descriptor = descriptor,
                    status = statuses[descriptor.platform] ?: AgentEnvStatus.UNKNOWN,
                    installProgress = progress[descriptor.platform],
                    isInstalling = installing == descriptor.platform,
                    workspaceReady = selectedWorkspace != null,
                    onInstall = { vm.install(descriptor.platform) },
                )
            }
        }
    }

    if (showWorkspacePicker) {
        WorkspacePickerSheet(
            workspaces = workspaces,
            selectedId = selectedWorkspaceId,
            onSelect = { id ->
                vm.selectWorkspace(id)
                showWorkspacePicker = false
            },
            onDismiss = { showWorkspacePicker = false },
        )
    }
}

@Composable
private fun WorkspaceSelectorCard(
    workspace: WorkspaceEntity?,
    hasWorkspaces: Boolean,
    onClick: () -> Unit,
    onNavigateToCreate: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.ServerStack01,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "安装目标工作区",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = workspace?.name ?: if (hasWorkspaces) "选择一个工作区" else "尚无工作区",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasWorkspaces) {
                Icon(HugeIcons.ArrowDown01, contentDescription = null)
            } else {
                TextButton(onClick = onNavigateToCreate) {
                    Text("去创建")
                }
            }
        }
    }
}

@Composable
private fun AgentItemCard(
    descriptor: AgentDescriptor,
    status: AgentEnvStatus,
    installProgress: AgentInstallProgress?,
    isInstalling: Boolean,
    workspaceReady: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoAIIcon(
                    name = stringResource(descriptor.labelRes),
                    modifier = Modifier.size(44.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(descriptor.labelRes),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        AgentStatusTag(status)
                    }
                    Text(
                        text = stringResource(descriptor.descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 安装中或上次安装失败时都展示进度块，避免失败详情随 installing 置空而被吞掉
            val failed = !isInstalling && installProgress?.phase == AgentInstallPhase.FAILED
            if (isInstalling || failed) {
                InstallProgressBlock(installProgress)
                if (failed) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = onInstall, enabled = workspaceReady) {
                            Text("重试")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "CLI 包：${descriptor.platform.cliPackage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (status == AgentEnvStatus.READY) {
                        OutlinedButton(onClick = onInstall) {
                            Text("重新安装")
                        }
                    } else {
                        Button(onClick = onInstall, enabled = workspaceReady) {
                            Text("安装")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentStatusTag(status: AgentEnvStatus) {
    val (text, type) = when (status) {
        AgentEnvStatus.READY -> "已安装" to TagType.SUCCESS
        AgentEnvStatus.NODE_MISSING -> "缺运行环境" to TagType.WARNING
        AgentEnvStatus.CLI_MISSING -> "未安装" to TagType.WARNING
        AgentEnvStatus.NO_ROOTFS -> "工作区未就绪" to TagType.ERROR
        AgentEnvStatus.UNKNOWN -> "检测中…" to TagType.INFO
    }
    Tag(type = type) {
        Text(text)
    }
}

@Composable
private fun InstallProgressBlock(progress: AgentInstallProgress?) {
    val phase = progress?.phase
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (phase == AgentInstallPhase.DONE || phase == AgentInstallPhase.FAILED) {
                LinearProgressIndicator(
                    progress = { if (phase == AgentInstallPhase.DONE) 1f else 0f },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.weight(1f))
            }
            if (phase == AgentInstallPhase.FAILED) {
                Text(
                    text = "失败",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            text = progress?.detail ?: "准备中…",
            style = MaterialTheme.typography.bodySmall,
            color = if (phase == AgentInstallPhase.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun WorkspacePickerSheet(
    workspaces: List<WorkspaceEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "选择安装目标工作区",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            workspaces.forEach { workspace ->
                Card(
                    onClick = { onSelect(workspace.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = workspace.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = workspace.root,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (workspace.id == selectedId) {
                            Icon(
                                imageVector = HugeIcons.CheckmarkCircle02,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (workspaces.isEmpty()) {
                Text(
                    text = "暂无工作区，请先在「工作区」页面创建并安装系统环境",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
