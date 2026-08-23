package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_IMAGE_GENERATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SELF_HOSTED_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.tools.ToolCapabilityCatalog
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

private data class PromptEntry(
    val title: String,
    val desc: String,
    val value: String,
)

@Composable
fun SettingGlobalPromptPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showResetDialog by remember { mutableStateOf(false) }

    val globalPrompt = settings.globalPrompt
    val promptEntries = remember(settings) {
        listOf(
            PromptEntry("全局提示词", "对所有对话生效，优先级最高，置于系统提示最前", globalPrompt),
            PromptEntry("标题生成", "生成会话标题时使用", settings.titlePrompt),
            PromptEntry("建议生成", "生成聊天建议时使用", settings.suggestionPrompt),
            PromptEntry("翻译", "AI 翻译时使用", settings.translatePrompt),
            PromptEntry("OCR", "图片文字识别时使用", settings.ocrPrompt),
            PromptEntry("上下文压缩", "长对话自动压缩时使用", settings.compressPrompt),
            PromptEntry("图片生成", "图片生成模型调用时使用", settings.imageGenerationPrompt),
            PromptEntry("记忆", "记忆摘要与嵌入时使用", settings.memoryPrompt),
            PromptEntry("自托管", "自托管模型调用时使用", settings.selfHostedPrompt),
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = "全局提示词")
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(HugeIcons.Refresh03, contentDescription = "恢复默认")
                    }
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = {
                            Text(
                                text = "全局提示词（最高优先级）",
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "会注入到每一次对话的系统提示最前，适合放置始终生效的规则或偏好。留空表示不启用。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedTextField(
                                    value = globalPrompt,
                                    onValueChange = { value ->
                                        vm.updateSettings(settings.copy(globalPrompt = value))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 4,
                                    maxLines = 12,
                                    placeholder = { Text("例如：请始终用简体中文回答。") },
                                )
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("内部提示词") },
                ) {
                    promptEntries.drop(1).forEach { entry ->
                        item {
                            PromptEditItem(
                                entry = entry,
                                onValueChange = { newValue ->
                                    vm.updateSettings(
                                        when (entry.title) {
                                            "标题生成" -> settings.copy(titlePrompt = newValue)
                                            "建议生成" -> settings.copy(suggestionPrompt = newValue)
                                            "翻译" -> settings.copy(translatePrompt = newValue)
                                            "OCR" -> settings.copy(ocrPrompt = newValue)
                                            "上下文压缩" -> settings.copy(compressPrompt = newValue)
                                            "图片生成" -> settings.copy(imageGenerationPrompt = newValue)
                                            "记忆" -> settings.copy(memoryPrompt = newValue)
                                            "自托管" -> settings.copy(selfHostedPrompt = newValue)
                                            else -> settings
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            item {
                val toolEntries = ToolCapabilityCatalog.entries
                    .groupBy { it.group }
                    .toSortedMap()
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("AI 工具清单") },
                ) {
                    item(
                        headlineContent = {
                            Text(
                                text = "AI 在对话中可执行的全部能力",
                                style = MaterialTheme.typography.titleSmall
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "这里列出软件授予 AI 的全部工具能力。开启「局域工具 → AI System Control」后，AI 可通过全能控制工具读写设置、切换模型、管理助手与插件、读写提示词。在全局提示词中（上方）可用中文描述你想赋予 AI 的权限。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                    )
                    toolEntries.forEach { (group, entries) ->
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = group,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                entries.forEach { entry ->
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                        )
                                        Text(
                                            text = entry.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认提示词") },
            text = { Text("将全局提示词与所有内部提示词恢复为默认值，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings(
                        settings.copy(
                            globalPrompt = "",
                            titlePrompt = DEFAULT_TITLE_PROMPT,
                            suggestionPrompt = DEFAULT_SUGGESTION_PROMPT,
                            translatePrompt = DEFAULT_TRANSLATION_PROMPT,
                            ocrPrompt = DEFAULT_OCR_PROMPT,
                            compressPrompt = DEFAULT_COMPRESS_PROMPT,
                            imageGenerationPrompt = DEFAULT_IMAGE_GENERATION_PROMPT,
                            memoryPrompt = DEFAULT_MEMORY_PROMPT,
                            selfHostedPrompt = DEFAULT_SELF_HOSTED_PROMPT,
                        )
                    )
                    showResetDialog = false
                }) {
                    Text("恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun PromptEditItem(
    entry: PromptEntry,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = entry.desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = entry.value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
        )
    }
}
