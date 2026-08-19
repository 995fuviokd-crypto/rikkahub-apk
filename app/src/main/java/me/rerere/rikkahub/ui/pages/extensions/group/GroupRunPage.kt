package me.rerere.rikkahub.ui.pages.extensions.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun GroupRunPage(
    runId: String,
    vm: GroupRunVM = koinViewModel(parameters = { parametersOf(runId) }),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val run by vm.run.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val groupName by vm.groupName.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        Text(groupName.ifBlank { "群组运行" })
                        run?.let {
                            Text(
                                text = it.status.label(),
                                style = MaterialTheme.typography.labelSmall,
                                color = it.status.color(),
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                GroupMessageTimeline(messages)
            }
        }
    }
}

fun RunStatus.label(): String = when (this) {
    RunStatus.RUNNING -> "进行中"
    RunStatus.SUCCESS -> "成功"
    RunStatus.FAILED -> "失败"
    RunStatus.STOPPED -> "已停止"
}

fun RunStatus.color(): androidx.compose.ui.graphics.Color = when (this) {
    RunStatus.RUNNING -> androidx.compose.ui.graphics.Color(0xFF2962FF)
    RunStatus.SUCCESS -> androidx.compose.ui.graphics.Color(0xFF00C853)
    RunStatus.FAILED -> androidx.compose.ui.graphics.Color(0xFFFF1744)
    RunStatus.STOPPED -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
}
