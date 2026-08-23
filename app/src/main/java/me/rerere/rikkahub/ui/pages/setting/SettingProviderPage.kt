package me.rerere.rikkahub.ui.pages.setting

import android.net.Uri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import me.rerere.ai.provider.AgentMode
import me.rerere.ai.provider.AgentPlatform
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.agentMode
import me.rerere.ai.provider.withAgentMode
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.AgentModePicker
import me.rerere.rikkahub.ui.components.ai.ProviderBalanceText
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.decodeProviderSetting
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.ProviderLatencyTag
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var searchQuery by remember { mutableStateOf("") }
    var selectedModelType by remember { mutableStateOf<ModelType?>(null) }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.providers.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        vm.updateSettings(settings.copy(providers = newProviders))
    }

    val filteredProviders = remember(settings.providers, searchQuery, selectedModelType) {
        settings.providers.filter { provider ->
            val matchesQuery = searchQuery.isBlank() ||
                provider.name.contains(searchQuery, ignoreCase = true) ||
                provider.models.any { it.displayName.contains(searchQuery, ignoreCase = true) }
            val matchesType = selectedModelType == null || provider.models.any { it.type == selectedModelType }
            matchesQuery && matchesType
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.setting_provider_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    ImportProviderButton {
                        vm.updateSettings(
                            settings.copy(
                                providers = listOf(it.copyProvider(Uuid.random())) + settings.providers
                            )
                        )
                    }
                    AddProviderButton {
                        vm.updateSettings(
                            settings.copy(
                                providers = listOf(it) + settings.providers
                            )
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.setting_provider_page_search_providers)) },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
            )

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val filterChips = listOf(
                    null to "全部",
                    ModelType.CHAT to "对话",
                    ModelType.IMAGE to "图片",
                    ModelType.VIDEO to "视频",
                    ModelType.EMBEDDING to "嵌入",
                )
                filterChips.forEach { (type, label) ->
                    FilterChip(
                        selected = selectedModelType == type,
                        onClick = {
                            selectedModelType = type
                        },
                        label = { Text(label) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp) +
                    PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = lazyListState,
            ) {
                items(filteredProviders, key = { it.id }) { provider ->
                    ReorderableItem(
                        state = reorderableState,
                        key = provider.id
                    ) { isDragging ->
                        ProviderCard(
                            modifier = Modifier
                                .scale(if (isDragging) 0.95f else 1f)
                                .fillMaxWidth(),
                            provider = provider,
                            dragHandle = {
                                val haptic = LocalHapticFeedback.current
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier
                                        .longPressDraggableHandle(
                                            onDragStarted = {
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            }
                                        )
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.DragDropHorizontal,
                                        contentDescription = null
                                    )
                                }
                            },
                            onClick = {
                                navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                            },
                            onToggleEnabled = {
                                vm.updateSettings(
                                    settings.copy(
                                        providers = settings.providers.map {
                                            if (it.id == provider.id) it.copyProvider(enabled = !it.enabled) else it
                                        }
                                    )
                                )
                            },
                            onUpdateModel = { model ->
                                vm.updateSettings(
                                    settings.copy(
                                        providers = settings.providers.map {
                                            if (it.id == provider.id) it.editModel(model) else it
                                        }
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportProviderButton(
    onAdd: (ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }

    val scanQrCodeLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        handleQRResult(result, onAdd, toaster, context)
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            handleImageQRCode(it, onAdd, toaster, context)
        }
    }

    IconButton(
        onClick = {
            showImportDialog = true
        }
    ) {
        Icon(HugeIcons.FileImport, null)
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_provider_page_import_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_import_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                showImportDialog = false
                                scanQrCodeLauncher.launch(null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Camera01,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_scan_qr_code),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                showImportDialog = false
                                pickImageLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Image02,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_select_from_gallery),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showImportDialog = false },
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }
}

private fun handleQRResult(
    result: QRResult,
    onAdd: (ProviderSetting) -> Unit,
    toaster: com.dokar.sonner.ToasterState,
    context: android.content.Context
) {
    runCatching {
        when (result) {
            is QRResult.QRError -> {
                toaster.show(
                    context.getString(
                        R.string.setting_provider_page_scan_error,
                        result
                    ), type = ToastType.Error
                )
            }

            QRResult.QRMissingPermission -> {
                toaster.show(
                    context.getString(R.string.setting_provider_page_no_permission),
                    type = ToastType.Error
                )
            }

            is QRResult.QRSuccess -> {
                val setting = decodeProviderSetting(result.content.rawValue ?: "")
                onAdd(setting)
                toaster.show(
                    context.getString(R.string.setting_provider_page_import_success),
                    type = ToastType.Success
                )
            }

            QRResult.QRUserCanceled -> {}
        }
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}

private fun handleImageQRCode(
    uri: Uri,
    onAdd: (ProviderSetting) -> Unit,
    toaster: com.dokar.sonner.ToasterState,
    context: android.content.Context
) {
    runCatching {
        // 使用ImageUtils解析二维码
        val qrContent = ImageUtils.decodeQRCodeFromUri(context, uri)

        if (qrContent.isNullOrEmpty()) {
            toaster.show(
                context.getString(R.string.setting_provider_page_no_qr_found),
                type = ToastType.Error
            )
            return
        }

        val setting = decodeProviderSetting(qrContent)
        onAdd(setting)
        toaster.show(
            context.getString(R.string.setting_provider_page_import_success),
            type = ToastType.Success
        )
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_image_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}

@Composable
private fun AddProviderButton(onAdd: (ProviderSetting) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            showPicker = true
        }
    ) {
        Icon(HugeIcons.Add01, "Add")
    }

    if (showPicker) {
        AddProviderSheet(
            onDismiss = { showPicker = false },
            onSelect = { setting ->
                showPicker = false
                onAdd(setting)
            }
        )
    }
}

private val agentPlatformTemplates: List<AgentPlatformTemplate> = listOf(
    AgentPlatformTemplate(
        platform = me.rerere.ai.provider.AgentPlatform.GEMINI_CLI,
        labelRes = R.string.setting_provider_page_platform_agent_gemini_cli,
        descRes = R.string.setting_provider_page_platform_agent_gemini_cli_desc,
        defaultModelId = "gemini-cli",
    ),
    AgentPlatformTemplate(
        platform = me.rerere.ai.provider.AgentPlatform.CODEX,
        labelRes = R.string.setting_provider_page_platform_agent_codex,
        descRes = R.string.setting_provider_page_platform_agent_codex_desc,
        defaultModelId = "codex",
    ),
    AgentPlatformTemplate(
        platform = me.rerere.ai.provider.AgentPlatform.CLAUDE_CODE,
        labelRes = R.string.setting_provider_page_platform_agent_claude_code,
        descRes = R.string.setting_provider_page_platform_agent_claude_code_desc,
        defaultModelId = "claude-code",
    ),
    AgentPlatformTemplate(
        platform = me.rerere.ai.provider.AgentPlatform.ANTHROPIC_CLAUDE_CODE,
        labelRes = R.string.setting_provider_page_platform_agent_anthropic_claude_code,
        descRes = R.string.setting_provider_page_platform_agent_anthropic_claude_code_desc,
        defaultModelId = "claude",
    ),
    AgentPlatformTemplate(
        platform = me.rerere.ai.provider.AgentPlatform.OPENCODE,
        labelRes = R.string.setting_provider_page_platform_agent_opencode,
        descRes = R.string.setting_provider_page_platform_agent_opencode_desc,
        defaultModelId = "opencode",
    ),
    AgentPlatformTemplate(
        platform = me.rerere.ai.provider.AgentPlatform.DEEPSEEK_HARNESS,
        labelRes = R.string.setting_provider_page_platform_agent_dsh,
        descRes = R.string.setting_provider_page_platform_agent_dsh_desc,
        defaultModelId = "dsh",
    ),
)

private data class AgentPlatformTemplate(
    val platform: me.rerere.ai.provider.AgentPlatform,
    val labelRes: Int,
    val descRes: Int,
    val defaultModelId: String,
)

private data class ApiTemplate(
    val label: String,
    val desc: String,
    val factory: () -> ProviderSetting,
)

private val apiTemplates: List<ApiTemplate> = listOf(
    ApiTemplate(
        label = "OpenAI 兼容",
        desc = "OpenAI、DeepSeek、通义千问等 /v1 接口服务",
        factory = {
            ProviderSetting.OpenAI(
                id = Uuid.random(),
                name = "OpenAI 兼容",
                baseUrl = "https://api.openai.com/v1",
                enabled = true,
            )
        },
    ),
    ApiTemplate(
        label = "Google Gemini",
        desc = "Google Generative Language API",
        factory = {
            ProviderSetting.Google(
                id = Uuid.random(),
                name = "Google Gemini",
                enabled = true,
            )
        },
    ),
    ApiTemplate(
        label = "Anthropic Claude",
        desc = "Anthropic Messages API",
        factory = {
            ProviderSetting.Claude(
                id = Uuid.random(),
                name = "Anthropic Claude",
                enabled = true,
            )
        },
    ),
)

@Composable
private fun AddProviderSheet(
    onDismiss: () -> Unit,
    onSelect: (ProviderSetting) -> Unit,
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
            var searchQuery by remember { mutableStateOf("") }
            var agentsExpanded by remember { mutableStateOf(true) }
            var apiExpanded by remember { mutableStateOf(false) }
            val isSearching = searchQuery.isNotBlank()

            Text(
                text = stringResource(R.string.setting_provider_page_add_provider),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索供应商或 Agent 模式") },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
            )

            // ---- Agent 模式分组 ----
            val filteredAgents = if (isSearching) {
                agentPlatformTemplates.filter { template ->
                    val label = stringResource(template.labelRes)
                    val desc = stringResource(template.descRes)
                    label.contains(searchQuery, ignoreCase = true) ||
                        desc.contains(searchQuery, ignoreCase = true)
                }
            } else {
                agentPlatformTemplates
            }
            if (filteredAgents.isNotEmpty() && (isSearching || agentsExpanded)) {
                GroupHeader(
                    title = "Agent 模式",
                    subtitle = "${agentPlatformTemplates.size} 种编码智能体，安装后即可绑定",
                    expanded = agentsExpanded,
                    onToggle = { agentsExpanded = !agentsExpanded },
                )
                if (agentsExpanded || isSearching) {
                    filteredAgents.forEach { template ->
                        val label = stringResource(template.labelRes)
                        ProviderTemplateItem(
                            name = label,
                            desc = stringResource(template.descRes),
                            tag = "Agent",
                            onClick = {
                                onSelect(
                                    ProviderSetting.OpenAI(
                                        id = Uuid.random(),
                                        name = label,
                                        baseUrl = "",
                                        apiKey = "",
                                        enabled = true,
                                        models = listOf(
                                            Model(
                                                modelId = template.defaultModelId,
                                                displayName = label,
                                                abilities = listOf(
                                                    me.rerere.ai.provider.ModelAbility.TOOL,
                                                    me.rerere.ai.provider.ModelAbility.REASONING,
                                                ),
                                                platformAgent = template.platform,
                                            )
                                        ),
                                    )
                                )
                            }
                        )
                    }
                }
            }

            // ---- 标准 API 分组 ----
            val filteredApis = if (isSearching) {
                apiTemplates.filter { template ->
                    template.label.contains(searchQuery, ignoreCase = true) ||
                        template.desc.contains(searchQuery, ignoreCase = true)
                }
            } else {
                apiTemplates
            }
            if (filteredApis.isNotEmpty() && (isSearching || apiExpanded)) {
                GroupHeader(
                    title = "标准 API",
                    subtitle = "通过 REST 接口接入的通用供应商",
                    expanded = apiExpanded,
                    onToggle = { apiExpanded = !apiExpanded },
                )
                if (apiExpanded || isSearching) {
                    filteredApis.forEach { template ->
                        ProviderTemplateItem(
                            name = template.label,
                            desc = template.desc,
                            tag = "API",
                            onClick = {
                                onSelect(template.factory())
                            }
                        )
                    }
                }
            }

            if (filteredAgents.isEmpty() && filteredApis.isEmpty()) {
                Text(
                    text = "未找到匹配项",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ProviderTemplateItem(
    name: String,
    desc: String,
    tag: String,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = name,
                modifier = Modifier.size(40.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Tag(type = if (tag == "Agent") TagType.INFO else TagType.DEFAULT) {
                        Text(tag)
                    }
                }
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = 0.7f)) {
                        Text(
                            text = desc,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Icon(
                imageVector = HugeIcons.Add01,
                contentDescription = stringResource(R.string.setting_provider_page_add)
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit,
    onClick: () -> Unit,
    onToggleEnabled: () -> Unit,
    onUpdateModel: (Model) -> Unit,
) {
    val agentModel = provider.models.firstOrNull { it.platformAgent != null }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
        onClick = {
            onClick()
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = provider.name,
                modifier = Modifier.size(48.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        color = if (provider.enabled) {
                            LocalContentColor.current
                        } else {
                            LocalContentColor.current.copy(alpha = 0.5f)
                        }
                    )
                    Switch(
                        checked = provider.enabled,
                        onCheckedChange = { onToggleEnabled() },
                        modifier = Modifier.scale(0.8f),
                    )
                }
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = 0.7f)) {
                        provider.shortDescription()
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = if (provider.enabled) TagType.SUCCESS else TagType.WARNING) {
                        Text(stringResource(if (provider.enabled) R.string.setting_provider_page_enabled else R.string.setting_provider_page_disabled))
                    }
                    Tag(type = TagType.INFO) {
                        Text(
                            stringResource(
                                R.string.setting_provider_page_model_count,
                                provider.models.size
                            )
                        )
                    }
                    ProviderLatencyTag(providerSetting = provider)
                    ProviderBalanceText(providerSetting = provider)
                    if (agentModel != null) {
                        AgentBindingTag(
                            model = agentModel,
                            onUpdateModel = onUpdateModel,
                        )
                    }
                }
            }
            dragHandle()
        }
    }
}

@Composable
private fun AgentBindingTag(
    model: Model,
    onUpdateModel: (Model) -> Unit,
) {
    val platform = model.platformAgent ?: return
    var showModePicker by remember { mutableStateOf(false) }

    if (platform.supportedModes.isNotEmpty()) {
        val modeLabel = model.agentMode()?.label() ?: "默认"
        Tag(
            type = TagType.INFO,
            onClick = { showModePicker = true },
        ) {
            Text("${platform.label()} · $modeLabel")
        }
        if (showModePicker) {
            AgentModePicker(
                currentMode = model.agentMode(),
                onDismissRequest = { showModePicker = false },
                onSelect = { mode ->
                    showModePicker = false
                    onUpdateModel(model.withAgentMode(mode))
                },
            )
        }
    } else {
        Tag(type = TagType.INFO) {
            Text(platform.label())
        }
    }
}

@Composable
private fun AgentPlatform.label(): String = stringResource(
    when (this) {
        AgentPlatform.CODEX -> R.string.setting_provider_page_platform_agent_codex
        AgentPlatform.CLAUDE_CODE -> R.string.setting_provider_page_platform_agent_claude_code
        AgentPlatform.GEMINI_CLI -> R.string.setting_provider_page_platform_agent_gemini_cli
        AgentPlatform.ANTHROPIC_CLAUDE_CODE -> R.string.setting_provider_page_platform_agent_anthropic_claude_code
        AgentPlatform.OPENCODE -> R.string.setting_provider_page_platform_agent_opencode
        AgentPlatform.DEEPSEEK_HARNESS -> R.string.setting_provider_page_platform_agent_dsh
    }
)

@Composable
private fun AgentMode.label(): String = stringResource(
    when (this) {
        AgentMode.STANDARD -> R.string.agent_mode_standard
        AgentMode.CODE -> R.string.agent_mode_code
        AgentMode.MINIMAL -> R.string.agent_mode_minimal
        AgentMode.CORDIS -> R.string.agent_mode_cordis
    }
)
