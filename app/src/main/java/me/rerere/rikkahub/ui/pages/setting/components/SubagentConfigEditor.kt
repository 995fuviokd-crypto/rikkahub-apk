package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.AgentSubagentConfig
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelSelector

/**
 * 子代理（Subagent）委派配置编辑器。
 *
 * 配置挂在 [agentModel] 上（每模型一份）。用于「供应商详情页」和「Agent 模式管理页」两处，
 * 通过 [onEdit] 将更新后的整个 Provider 回传上层持久化。
 */
@Composable
fun SubagentConfigEditor(
    provider: ProviderSetting,
    providers: List<ProviderSetting>,
    agentModel: Model,
    onEdit: (ProviderSetting) -> Unit,
) {
    val platformAgent = agentModel.platformAgent
    val agentArguments = agentModel.agentArguments
    val agentEnvironment = agentModel.agentEnvironment
    val subagentConfig = agentModel.agentSubagent ?: AgentSubagentConfig()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.setting_provider_page_subagent_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.setting_provider_page_subagent_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = subagentConfig.enabled,
            onCheckedChange = { enabled ->
                onEdit(
                    provider.editModel(
                        agentModel.copy(
                            platformAgent = platformAgent,
                            agentArguments = agentArguments,
                            agentEnvironment = agentEnvironment,
                            agentSubagent = subagentConfig.copy(enabled = enabled),
                        )
                    )
                )
            },
        )
    }

    AnimatedVisibility(visible = subagentConfig.enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 引擎选择：自动切换 / RikkaHub 内置 / DSH 自带
            val engineOptions = listOf(
                AgentSubagentConfig.ENGINE_AUTO,
                AgentSubagentConfig.ENGINE_BUILT_IN,
                AgentSubagentConfig.ENGINE_DSH,
            )
            val engineLabels = listOf(
                stringResource(R.string.setting_provider_page_subagent_engine_auto),
                stringResource(R.string.setting_provider_page_subagent_engine_built_in),
                stringResource(R.string.setting_provider_page_subagent_engine_dsh),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                engineOptions.forEachIndexed { index, engine ->
                    SegmentedButton(
                        selected = subagentConfig.engine == engine,
                        onClick = {
                            onEdit(
                                provider.editModel(
                                    agentModel.copy(
                                        platformAgent = platformAgent,
                                        agentArguments = agentArguments,
                                        agentEnvironment = agentEnvironment,
                                        agentSubagent = subagentConfig.copy(engine = engine),
                                    )
                                )
                            )
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = engineOptions.size),
                    ) {
                        Text(engineLabels[index], maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }

            // 最大委派深度
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_subagent_max_depth),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SingleChoiceSegmentedButtonRow {
                    (1..3).forEachIndexed { index, depth ->
                        SegmentedButton(
                            selected = subagentConfig.maxDepth == depth,
                            onClick = {
                                onEdit(
                                    provider.editModel(
                                        agentModel.copy(
                                            platformAgent = platformAgent,
                                            agentArguments = agentArguments,
                                            agentEnvironment = agentEnvironment,
                                            agentSubagent = subagentConfig.copy(maxDepth = depth),
                                        )
                                    )
                                )
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            label = { Text(depth.toString()) },
                        )
                    }
                }
            }
            // 子代理模型：可跨提供商选择；清除后跟随主模型
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_subagent_model),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ModelSelector(
                    modelId = subagentConfig.modelId,
                    providers = providers,
                    type = ModelType.CHAT,
                    allowClear = true,
                    onSelect = { selected ->
                        onEdit(
                            provider.editModel(
                                agentModel.copy(
                                    platformAgent = platformAgent,
                                    agentArguments = agentArguments,
                                    agentEnvironment = agentEnvironment,
                                    agentSubagent = subagentConfig.copy(
                                        modelId = if (selected.modelId.isBlank()) null else selected.id,
                                    ),
                                )
                            )
                        )
                    },
                )
            }
            if (subagentConfig.engine == AgentSubagentConfig.ENGINE_DSH) {
                Text(
                    stringResource(R.string.setting_provider_page_subagent_dsh_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
