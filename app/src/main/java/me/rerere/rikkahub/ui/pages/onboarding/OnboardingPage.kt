package me.rerere.rikkahub.ui.pages.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.agent.AcpEnvironmentManager
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.hooks.writeBooleanPreference
import me.rerere.rikkahub.ui.pages.extensions.workspace.VmCatalog
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.koin.androidx.compose.koinViewModel

/** 向导可选的 Provider 模板（内置模板的热门子集）。 */
private val ONBOARDING_TEMPLATES = listOf(
    "OpenAI", "Gemini", "DeepSeek", "硅基流动", "月之暗面", "OpenRouter",
    "阿里云百炼", "智谱AI开放平台", "火山引擎", "xAI",
)

/** 环境初始化阶段 */
enum class OnboardingEnvStage {
    IDLE,
    CREATING,
    ROOTFS,
    TOOLS,
    DONE,
}

class OnboardingVM(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val workspaceRepository: WorkspaceRepository,
    private val acpEnvironmentManager: AcpEnvironmentManager,
) : ViewModel() {

    /** 拉取模型列表（同时作为连通性测试）。 */
    suspend fun listModels(provider: ProviderSetting): Result<List<Model>> = runCatching {
        providerManager.getProviderByType(provider)
            .listModels(provider)
            .sortedBy { it.modelId }
            .toList()
    }

    /**
     * 基础环境初始化: 创建默认工作区 → 安装 rootfs → 安装基础工具链
     * (Node.js 离线优先 / Git / curl 等)。任一阶段失败返回 Result.failure,
     * 已创建的工作区保留(状态 DISABLED/BROKEN), 用户可重试(幂等重装)或到工作区页处理。
     */
    suspend fun setupWorkspaceEnvironment(
        imageUrl: String,
        onStage: (OnboardingEnvStage) -> Unit,
        onProgress: (RootfsInstallProgress) -> Unit,
    ): Result<String> = runCatching {
        onStage(OnboardingEnvStage.CREATING)
        val workspace = workspaceRepository.create("默认工作区")
        try {
            onStage(OnboardingEnvStage.ROOTFS)
            workspaceRepository.installRootfs(workspace.id, imageUrl, onProgress)
            onStage(OnboardingEnvStage.TOOLS)
            installBaseToolchain(workspace)
            onStage(OnboardingEnvStage.DONE)
            workspace.id
        } catch (e: Throwable) {
            // 失败/取消时清理刚创建的工作区: 重试从全新状态开始, 不积累半成品记录
            runCatching { workspaceRepository.delete(workspace.id) }
            throw e
        }
    }

    /** 基础工具链: Node.js(离线包优先, 失败回退包管理器) + git/curl/wget/unzip/jq */
    private suspend fun installBaseToolchain(workspace: WorkspaceEntity) {
        // Node.js: APK 内置离线运行时, 零网络
        val nodeOk = runCatching {
            acpEnvironmentManager.installNodeOfflineOnly(workspace.root)
        }.getOrDefault(false) && commandOk(
            workspace.id,
            "command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && echo __OK__ || echo __NO__",
        )
        // Node 离线失败时并入包管理器统一安装; git/curl 等顺带装上, 单项失败不阻塞整体
        val nodePart = if (nodeOk) "" else "nodejs npm"
        runCatching {
            workspaceRepository.executeCommand(
                workspace.id,
                buildString {
                    append("export DEBIAN_FRONTEND=noninteractive; ")
                    append("if command -v apk >/dev/null 2>&1; then ")
                    append("apk add --no-cache -q bash git curl wget unzip jq $nodePart >/dev/null 2>&1 || true; ")
                    append("elif command -v apt-get >/dev/null 2>&1; then ")
                    append("(apt-get update -qq >/dev/null 2>&1 || true); ")
                    append("apt-get install -y -qq git curl wget unzip jq $nodePart >/dev/null 2>&1 || true; ")
                    append("fi")
                },
                timeoutMillis = 600_000,
            )
        }.onFailure {
            android.util.Log.w("OnboardingVM", "base toolchain install failed", it)
        }
    }

    private suspend fun commandOk(workspaceId: String, command: String): Boolean = runCatching {
        val result = workspaceRepository.executeCommand(workspaceId, command)
        result.exitCode == 0 && result.stdout.contains("__OK__")
    }.getOrDefault(false)

    /** 保存 Provider（替换同 id 的内置模板项）与默认模型。 */
    fun saveProvider(provider: ProviderSetting, selectedModel: Model?, onDone: () -> Unit) {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            val newProviders = settings.providers.map { existing ->
                if (existing.id == provider.id) {
                    applyProviderEdit(existing, provider)
                } else {
                    existing
                }
            }.ifEmpty { listOf(provider) }
            settingsStore.update(
                settings.copy(
                    providers = newProviders,
                    chatModelId = selectedModel?.id ?: settings.chatModelId,
                )
            )
            onDone()
        }
    }

    private fun applyProviderEdit(existing: ProviderSetting, edited: ProviderSetting): ProviderSetting {
        return when (existing) {
            is ProviderSetting.OpenAI -> {
                existing.copy(
                    apiKey = (edited as ProviderSetting.OpenAI).apiKey,
                    baseUrl = edited.baseUrl,
                    enabled = true,
                    models = edited.models.ifEmpty { existing.models },
                )
            }

            is ProviderSetting.Google -> {
                existing.copy(
                    apiKey = (edited as ProviderSetting.Google).apiKey,
                    enabled = true,
                    models = edited.models.ifEmpty { existing.models },
                )
            }

            is ProviderSetting.Claude -> {
                existing.copy(
                    apiKey = (edited as ProviderSetting.Claude).apiKey,
                    baseUrl = edited.baseUrl,
                    enabled = true,
                    models = edited.models.ifEmpty { existing.models },
                )
            }
        }
    }
}

@Composable
fun OnboardingPage(
    vm: OnboardingVM = koinViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = me.rerere.rikkahub.ui.context.LocalNavController.current
    var step by remember { mutableStateOf(0) }
    var selectedTemplate by remember { mutableStateOf<ProviderSetting?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<Model>>(emptyList()) }
    var selectedModel by remember { mutableStateOf<Model?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testError by remember { mutableStateOf<String?>(null) }
    // 环境初始化状态
    var envImageId by remember { mutableStateOf("ubuntu-25.10") }
    var envRunning by remember { mutableStateOf(false) }
    var envStage by remember { mutableStateOf(OnboardingEnvStage.IDLE) }
    var envProgress by remember { mutableStateOf<RootfsInstallProgress?>(null) }
    var envError by remember { mutableStateOf<String?>(null) }

    val templates = remember {
        DEFAULT_PROVIDERS.filter { it.name in ONBOARDING_TEMPLATES }
    }

    fun skipOnboarding() {
        context.writeBooleanPreference("onboarding_completed", true)
        me.rerere.rikkahub.utils.navigateToChatPage(navController)
    }

    // 环境初始化完成后统一出口: 写完成标记进聊天页
    fun finishOnboarding() {
        context.writeBooleanPreference("onboarding_completed", true)
        me.rerere.rikkahub.utils.navigateToChatPage(navController)
    }

    fun startEnvSetup() {
        val image = VmCatalog.images.firstOrNull { it.id == envImageId } ?: return
        envRunning = true
        envError = null
        envStage = OnboardingEnvStage.IDLE
        envProgress = null
        scope.launch {
            vm.setupWorkspaceEnvironment(
                imageUrl = image.url,
                onStage = { envStage = it },
                onProgress = { envProgress = it },
            ).onSuccess {
                envRunning = false
            }.onFailure { error ->
                envRunning = false
                envError = error.message ?: error.toString()
            }
        }
    }

    // 系统返回键：向导内先退上一步，第一步时才允许退出
    androidx.activity.compose.BackHandler(enabled = step > 0) {
        step--
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
                actions = {
                    TextButton(onClick = ::skipOnboarding) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(
                progress = { (step + 1) / 4f },
                modifier = Modifier.fillMaxWidth(),
            )

            when (step) {
                // 步骤 1: 选择 Provider 模板
                0 -> {
                    Text(
                        text = stringResource(R.string.onboarding_pick_provider),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(templates, key = { it.id }) { template ->
                            val selected = selectedTemplate?.id == template.id
                            Surface(
                                onClick = {
                                    selectedTemplate = template
                                    baseUrl = when (template) {
                                        is ProviderSetting.OpenAI -> template.baseUrl
                                        is ProviderSetting.Claude -> template.baseUrl
                                        is ProviderSetting.Google -> ""
                                    }
                                    apiKey = ""
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            ) {
                                Text(
                                    text = template.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { step = 1 },
                        enabled = selectedTemplate != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.onboarding_next)) }
                }

                // 步骤 2: 填写 BaseURL + API Key
                1 -> {
                    Text(
                        text = stringResource(R.string.onboarding_fill_key, selectedTemplate?.name ?: ""),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val openAiTemplate = selectedTemplate as? ProviderSetting.OpenAI
                    val claudeTemplate = selectedTemplate as? ProviderSetting.Claude
                    if (openAiTemplate != null || claudeTemplate != null) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text(stringResource(R.string.onboarding_base_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.onboarding_api_key)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = { step = 0 },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.onboarding_back)) }
                        Button(
                            onClick = { step = 2 },
                            enabled = apiKey.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.onboarding_next)) }
                    }
                }

                // 步骤 3: 测试连通 + 选择模型
                2 -> {
                    Text(
                        text = stringResource(R.string.onboarding_test_and_pick),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val provider = selectedTemplate?.let { template ->
                        when (template) {
                            is ProviderSetting.OpenAI -> template.copy(
                                apiKey = apiKey,
                                baseUrl = baseUrl.ifBlank { template.baseUrl },
                            )

                            is ProviderSetting.Google -> template.copy(apiKey = apiKey)
                            is ProviderSetting.Claude -> template.copy(
                                apiKey = apiKey,
                                baseUrl = baseUrl.ifBlank { template.baseUrl },
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                if (provider != null) {
                                    testing = true
                                    testError = null
                                    scope.launch {
                                        vm.listModels(provider)
                                            .onSuccess { models = it }
                                            .onFailure { testError = it.message ?: it.toString() }
                                        testing = false
                                    }
                                }
                            },
                            enabled = !testing && provider != null,
                        ) {
                            if (testing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(stringResource(R.string.onboarding_test))
                            }
                        }
                        if (models.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.onboarding_models_loaded, models.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    testError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(1),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(models, key = { it.id }) { model ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                RadioButton(
                                    selected = selectedModel?.id == model.id,
                                    onClick = { selectedModel = model },
                                )
                                Text(
                                    text = model.displayName.ifBlank { model.modelId },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = { step = 1 },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.onboarding_back)) }
                        Button(
                            onClick = {
                                if (provider != null) {
                                    vm.saveProvider(provider, selectedModel) { }
                                    step = 3
                                }
                            },
                            enabled = models.isNotEmpty() || selectedModel != null,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.onboarding_next)) }
                    }
                }

                // 步骤 4: 基础环境初始化（可选）
                3 -> {
                    Text(
                        text = stringResource(R.string.onboarding_env_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_env_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 镜像选择
                    if (!envRunning && envStage != OnboardingEnvStage.DONE) {
                        VmCatalog.images.take(3).forEach { image ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                RadioButton(
                                    selected = envImageId == image.id,
                                    onClick = { envImageId = image.id },
                                )
                                Column {
                                    Text(
                                        text = stringResource(image.labelRes),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(image.descRes) +
                                            " · " + stringResource(R.string.onboarding_env_size, image.sizeHintMb),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    // 阶段与进度
                    if (envRunning || envStage == OnboardingEnvStage.DONE) {
                        val stageText = when (envStage) {
                            OnboardingEnvStage.CREATING -> stringResource(R.string.onboarding_env_creating)
                            OnboardingEnvStage.ROOTFS -> when (envProgress?.stage) {
                                RootfsInstallStage.EXTRACTING -> stringResource(
                                    R.string.onboarding_env_extracting,
                                    envProgress?.entriesExtracted ?: 0,
                                )

                                else -> stringResource(R.string.onboarding_env_downloading)
                            }

                            OnboardingEnvStage.TOOLS -> stringResource(R.string.onboarding_env_tools)
                            OnboardingEnvStage.DONE -> stringResource(R.string.onboarding_env_done)
                            OnboardingEnvStage.IDLE -> ""
                        }
                        Text(
                            text = stageText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val progress = envProgress
                        val totalBytes = progress?.totalBytes
                        val fraction = totalBytes?.takeIf { it > 0 }
                            ?.let { (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f) }
                        if (envStage == OnboardingEnvStage.ROOTFS && progress?.stage == RootfsInstallStage.DOWNLOADING && fraction != null && totalBytes != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(
                                    R.string.onboarding_env_progress_bytes,
                                    progress.bytesRead / 1024 / 1024,
                                    totalBytes / 1024 / 1024,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (envRunning && envStage != OnboardingEnvStage.DONE) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    envError?.let { error ->
                        Text(
                            text = stringResource(R.string.onboarding_env_failed, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = ::finishOnboarding,
                            enabled = !envRunning,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.onboarding_env_skip)) }
                        Button(
                            onClick = {
                                when {
                                    envStage == OnboardingEnvStage.DONE -> finishOnboarding()
                                    envError != null -> startEnvSetup()
                                    else -> startEnvSetup()
                                }
                            },
                            enabled = !envRunning,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    when {
                                        envStage == OnboardingEnvStage.DONE -> R.string.onboarding_finish
                                        envError != null -> R.string.onboarding_env_retry
                                        else -> R.string.onboarding_env_start
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
