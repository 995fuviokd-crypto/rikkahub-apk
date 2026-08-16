package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * 工作区多选页：一个助手可绑定一个主工作区 + 多个附加工作区。
 * 附加工作区在工具侧以 _2/_3 后缀暴露，首个为主工作区。
 */
@Composable
fun AssistantWorkspacePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_workspace))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_workspace))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_workspace_desc))
                    },
                ) {
                    val selectedIds = assistant.effectiveWorkspaceIds.map { it.toString() }.toSet()
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedIds.isEmpty(),
                            onClick = {
                                vm.update(
                                    assistant.copy(
                                        workspaceIds = emptySet(),
                                        workspaceId = null,
                                    )
                                )
                            },
                            label = { Text(stringResource(R.string.workspace_no_binding)) },
                        )
                        workspaces.forEach { workspace ->
                            val isSelected = workspace.id in selectedIds
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    // 集合保持选择顺序: 第一个为主工作区
                                    val newIds = if (isSelected) {
                                        selectedIds - workspace.id
                                    } else {
                                        selectedIds + workspace.id
                                    }
                                    val newUuids = newIds.mapNotNull { id ->
                                        runCatching { Uuid.parse(id) }.getOrNull()
                                    }
                                    vm.update(
                                        assistant.copy(
                                            workspaceIds = newUuids.toSet(),
                                            workspaceId = newUuids.firstOrNull(),
                                        )
                                    )
                                },
                                label = {
                                    Text(
                                        text = workspace.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
