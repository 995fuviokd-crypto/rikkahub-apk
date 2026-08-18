package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.service.KeepAliveService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasIgnoreBatteryOptimizationsPermission
import me.rerere.rikkahub.utils.openBatteryOptimizationSettings
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesGeneralPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_general))
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                    "create_new_conversation_on_start",
                    true
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_multi_route_concurrent_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_multi_route_concurrent_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.multiRouteConcurrent,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(multiRouteConcurrent = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("消息撤回：分段截断") },
                        supportingContent = { Text("关闭时撤回整条消息；开启时按边界标点截断最后一段") },
                        trailingContent = {
                            Switch(
                                checked = settings.recallSegmented,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(recallSegmented = it))
                                }
                            )
                        },
                    )
                    if (settings.recallSegmented) {
                        item(
                            headlineContent = { Text("撤回边界标点") },
                            supportingContent = {
                                OutlinedTextField(
                                    value = settings.recallBoundaryPunctuation,
                                    onValueChange = { value ->
                                        vm.updateSettings(settings.copy(recallBoundaryPunctuation = value))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text("回滚工具副作用") },
                        supportingContent = { Text("撤回时还原工作区文件、记忆与剪贴板等可回滚副作用") },
                        trailingContent = {
                            Switch(
                                checked = settings.recallRollbackEnabled,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(recallRollbackEnabled = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("撤回后告知 AI") },
                        supportingContent = { Text("撤回时在上下文中告知 AI 已撤回的内容") },
                        trailingContent = {
                            Switch(
                                checked = settings.recallInformedAi,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(recallInformedAi = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_screen_resolution_override_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_screen_resolution_override_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.screenResolutionOverrideEnabled,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(screenResolutionOverrideEnabled = it))
                                }
                            )
                        },
                    )
                    if (settings.screenResolutionOverrideEnabled) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_screen_resolution_override_size_title)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = if (settings.screenResolutionOverrideWidth > 0) {
                                            settings.screenResolutionOverrideWidth.toString()
                                        } else {
                                            ""
                                        },
                                        onValueChange = { value ->
                                            vm.updateSettings(
                                                settings.copy(
                                                    screenResolutionOverrideWidth = value.toIntOrNull() ?: 0
                                                )
                                            )
                                        },
                                        label = { Text(stringResource(R.string.setting_display_page_screen_resolution_override_width)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("×")
                                    OutlinedTextField(
                                        value = if (settings.screenResolutionOverrideHeight > 0) {
                                            settings.screenResolutionOverrideHeight.toString()
                                        } else {
                                            ""
                                        },
                                        onValueChange = { value ->
                                            vm.updateSettings(
                                                settings.copy(
                                                    screenResolutionOverrideHeight = value.toIntOrNull() ?: 0
                                                )
                                            )
                                        },
                                        label = { Text(stringResource(R.string.setting_display_page_screen_resolution_override_height)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc)) },
                        trailingContent = {
                            Switch(
                                checked = createNewConversationOnStart,
                                onCheckedChange = { createNewConversationOnStart = it }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.sendOnEnter,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(sendOnEnter = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showMessageJumper,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.showMessageJumper) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.messageJumperOnLeft,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it))
                                    }
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableAutoScroll,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableAutoScroll = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.useAppIconStyleLoadingIndicator,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(useAppIconStyleLoadingIndicator = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableBlurEffect,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableBlurEffect = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableMessageGenerationHapticEffect,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.skipCropImage,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(skipCropImage = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.pasteLongTextAsFile,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.pasteLongTextAsFile) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = displaySetting.pasteLongTextThreshold.toFloat(),
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
                                        },
                                        valueRange = 100f..10000f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${displaySetting.pasteLongTextThreshold}")
                                }
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableVolumeKeyScroll,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableVolumeKeyScroll = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.enableVolumeKeyScroll) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_ratio)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = displaySetting.volumeKeyScrollRatio,
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                        },
                                        valueRange = 0.25f..1.0f,
                                        steps = 2,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${(displaySetting.volumeKeyScrollRatio * 100).toInt()}%")
                                }
                            }
                        )
                    }
                }
            }

            item {
                val keepAliveContext = LocalContext.current
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_background_keep_alive)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_background_keep_alive_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_background_keep_alive_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.keepAliveEnabled,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(settings.copy(keepAliveEnabled = enabled))
                                    if (!enabled) {
                                        keepAliveContext.stopService(Intent(keepAliveContext, KeepAliveService::class.java))
                                    }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_ignore_battery_optimizations_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_ignore_battery_optimizations_desc)) },
                        trailingContent = {
                            TextButton(
                                onClick = { keepAliveContext.openBatteryOptimizationSettings() },
                            ) {
                                Text(
                                    stringResource(
                                        if (keepAliveContext.hasIgnoreBatteryOptimizationsPermission()) {
                                            R.string.setting_page_ignore_battery_optimizations_done
                                        } else {
                                            R.string.setting_page_ignore_battery_optimizations_action
                                        }
                                    )
                                )
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_tts_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.ttsOnlyReadQuoted,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadQuoted = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.ttsOnlyReadOutsideBrackets,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadOutsideBrackets = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.autoPlayTTSAfterGeneration,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(autoPlayTTSAfterGeneration = it))
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}
