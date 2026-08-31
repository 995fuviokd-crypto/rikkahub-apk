package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_IMAGE_GENERATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SELF_HOSTED_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
        )
    }
)

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val VIDEO_GENERATION_MODEL = stringPreferencesKey("video_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val GLOBAL_PROMPT = stringPreferencesKey("global_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val OCR_ENABLED = booleanPreferencesKey("ocr_enabled")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val MEMORY_MODEL = stringPreferencesKey("memory_model")
        val SELF_HOSTED_MODEL = stringPreferencesKey("self_hosted_model")
        val IMAGE_GENERATION_PROMPT = stringPreferencesKey("image_generation_prompt")
        val MEMORY_PROMPT = stringPreferencesKey("memory_prompt")
        val SELF_HOSTED_PROMPT = stringPreferencesKey("self_hosted_prompt")
        val AUTO_COMPRESS_ENABLED = booleanPreferencesKey("auto_compress_enabled")
        val AUTO_COMPRESS_CONTEXT_PERCENT = intPreferencesKey("auto_compress_context_percent")
        val AUTO_COMPRESS_MAX_MODE = booleanPreferencesKey("auto_compress_max_mode")
        val AUTO_RECONNECT_ENABLED = booleanPreferencesKey("auto_reconnect_enabled")
        val AUTO_RECONNECT_MAX_RETRIES = intPreferencesKey("auto_reconnect_max_retries")
        // 多线路并发：请求自动探测同名模型的多条 provider 线路，并发竞速 + 故障转移
        val MULTI_ROUTE_CONCURRENT = booleanPreferencesKey("multi_route_concurrent")
        val MAX_CONCURRENT_ROUTES = intPreferencesKey("max_concurrent_routes")
        val GLOBAL_TOOL_SCRIPTS = booleanPreferencesKey("global_tool_scripts")
        val GLOBAL_TOOL_ACCESSIBILITY = booleanPreferencesKey("global_tool_accessibility")
        val GLOBAL_TOOL_POWER_MANAGEMENT = booleanPreferencesKey("global_tool_power_management")
        val GLOBAL_TOOL_TERMUX = booleanPreferencesKey("global_tool_termux")
        // 插件配置：插件 id -> 配置对象 JSON 文本（声明 config schema 的插件由用户编辑，热生效）
        val PLUGIN_CONFIGS = stringPreferencesKey("plugin_configs")

        // 消息撤回
        val RECALL_SEGMENTED = booleanPreferencesKey("recall_segmented")
        val RECALL_BOUNDARY_PUNCTUATION = stringPreferencesKey("recall_boundary_punctuation")
        val RECALL_ROLLBACK_ENABLED = booleanPreferencesKey("recall_rollback_enabled")
        val RECALL_INFORMED_AI = booleanPreferencesKey("recall_informed_ai")

        // 悬浮球：系统级悬浮窗，点击回到软件
        val FLOATING_BUBBLE_ENABLED = booleanPreferencesKey("floating_bubble_enabled")
        val FLOATING_BUBBLE_COLOR = stringPreferencesKey("floating_bubble_color")
        val FLOATING_BUBBLE_SIZE = intPreferencesKey("floating_bubble_size")
        // 悬浮球展开窗口：宽度/高度（dp），以及待办/实时输出标签开关
        val FLOATING_BUBBLE_EXPAND_WIDTH = intPreferencesKey("floating_bubble_expand_width")
        val FLOATING_BUBBLE_EXPAND_HEIGHT = intPreferencesKey("floating_bubble_expand_height")
        val FLOATING_BUBBLE_SHOW_TODO_TAB = booleanPreferencesKey("floating_bubble_show_todo_tab")
        val FLOATING_BUBBLE_SHOW_LIVE_TAB = booleanPreferencesKey("floating_bubble_show_live_tab")
        // 无操作 N 秒后悬浮球自动贴边弱化（0 = 禁用）
        val FLOATING_BUBBLE_AUTO_HIDE_SECONDS = intPreferencesKey("floating_bubble_auto_hide_seconds")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 网络代理
        val GLOBAL_PROXY = stringPreferencesKey("global_proxy")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // 屏幕显示缩放：手机屏幕真实呈现平板布局（软件渲染密度覆盖）
        val DISPLAY_SCALE_MODE = intPreferencesKey("display_scale_mode")
        val DISPLAY_SCALE_DENSITY_DPI = intPreferencesKey("display_scale_density_dpi")

        // 后台保活常驻通知：进应用即在前台服务消息栏显示"正在运行中"
        val KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")
        // 托管工具自动审批：开启后工作区/系统等需审批的工具调用自动通过（ask_user 除外）
        val AUTO_APPROVE_TOOLS = booleanPreferencesKey("auto_approve_tools")
        // 自主执行引导：开启后生成系统提示注入"连续调用工具直到任务完成"指令，避免中途停下汇报/询问
        val AUTONOMOUS_EXECUTION_ENABLED = booleanPreferencesKey("autonomous_execution_enabled")
        // 插件市场：已启用插件 id 集合（JSON）、市场索引仓库、GitHub 访问令牌
        val ENABLED_PLUGINS = stringPreferencesKey("enabled_plugins")
        val PLUGIN_MARKET_REPO = stringPreferencesKey("plugin_market_repo")
        val GITHUB_TOKEN = stringPreferencesKey("github_token")
        val TAVERN_IMPORTED_KEYS = stringSetPreferencesKey("tavern_imported_keys")
        val BUILTIN_MAKER_SKILL_CLEANUP = booleanPreferencesKey("builtin_maker_skill_cleanup")
    }

    private val dataStore = context.settingsStore

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelReasoningLevel = preferences[FAST_MODEL_REASONING_LEVEL]
                    ?.let { value -> ReasoningLevel.entries.find { it.name == value } }
                    ?: ReasoningLevel.AUTO,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                videoGenerationModelId = preferences[VIDEO_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                globalPrompt = preferences[GLOBAL_PROMPT] ?: "",
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.takeIf { it.isNotBlank() }?.let { Uuid.parse(it) },
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                ocrEnabled = preferences[OCR_ENABLED] ?: true,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                memoryModelId = preferences[MEMORY_MODEL]?.let { Uuid.parse(it) },
                selfHostedModelId = preferences[SELF_HOSTED_MODEL]?.let { Uuid.parse(it) },
                imageGenerationPrompt = preferences[IMAGE_GENERATION_PROMPT] ?: DEFAULT_IMAGE_GENERATION_PROMPT,
                memoryPrompt = preferences[MEMORY_PROMPT] ?: DEFAULT_MEMORY_PROMPT,
                selfHostedPrompt = preferences[SELF_HOSTED_PROMPT] ?: DEFAULT_SELF_HOSTED_PROMPT,
                autoCompressEnabled = preferences[AUTO_COMPRESS_ENABLED] ?: false,
                autoCompressContextPercent = preferences[AUTO_COMPRESS_CONTEXT_PERCENT] ?: 60,
                autoCompressMaxMode = preferences[AUTO_COMPRESS_MAX_MODE] ?: false,
                autoReconnectEnabled = preferences[AUTO_RECONNECT_ENABLED] ?: false,
                autoReconnectMaxRetries = preferences[AUTO_RECONNECT_MAX_RETRIES] ?: 3,
                multiRouteConcurrent = preferences[MULTI_ROUTE_CONCURRENT] ?: false,
                maxConcurrentRoutes = preferences[MAX_CONCURRENT_ROUTES] ?: 3,
                globalToolScripts = preferences[GLOBAL_TOOL_SCRIPTS] ?: false,
                globalToolAccessibility = preferences[GLOBAL_TOOL_ACCESSIBILITY] ?: false,
                globalToolPowerManagement = preferences[GLOBAL_TOOL_POWER_MANAGEMENT] ?: false,
                globalToolTermux = preferences[GLOBAL_TOOL_TERMUX] ?: false,
                recallSegmented = preferences[RECALL_SEGMENTED] ?: false,
                recallBoundaryPunctuation = preferences[RECALL_BOUNDARY_PUNCTUATION] ?: "。！？～",
                recallRollbackEnabled = preferences[RECALL_ROLLBACK_ENABLED] ?: true,
                recallInformedAi = preferences[RECALL_INFORMED_AI] ?: true,
                floatingBubbleEnabled = preferences[FLOATING_BUBBLE_ENABLED] ?: false,
                floatingBubbleColor = preferences[FLOATING_BUBBLE_COLOR]?.toLongOrNull() ?: 0xFF4F8EF7,
                floatingBubbleSize = preferences[FLOATING_BUBBLE_SIZE] ?: 48,
                floatingBubbleExpandWidth = preferences[FLOATING_BUBBLE_EXPAND_WIDTH] ?: 300,
                floatingBubbleExpandHeight = preferences[FLOATING_BUBBLE_EXPAND_HEIGHT] ?: 420,
                floatingBubbleShowTodoTab = preferences[FLOATING_BUBBLE_SHOW_TODO_TAB] ?: true,
                floatingBubbleShowLiveTab = preferences[FLOATING_BUBBLE_SHOW_LIVE_TAB] ?: true,
                floatingBubbleAutoHideSeconds = preferences[FLOATING_BUBBLE_AUTO_HIDE_SECONDS] ?: 15,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                globalProxy = preferences[GLOBAL_PROXY]?.let {
                    JsonInstant.decodeFromString<me.rerere.ai.provider.ProxyConfig>(it)
                },
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                networkSetting = JsonInstant.decodeFromString(preferences[NETWORK_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
                displayScaleMode = preferences[DISPLAY_SCALE_MODE] ?: 0,
                displayScaleDensityDpi = preferences[DISPLAY_SCALE_DENSITY_DPI] ?: 160,
                autoApproveTools = preferences[AUTO_APPROVE_TOOLS] != false,
                autonomousExecutionEnabled = preferences[AUTONOMOUS_EXECUTION_ENABLED] != false,
                // 后台保活常驻默认开启
                keepAliveEnabled = preferences[KEEP_ALIVE_ENABLED] != false,
                enabledPlugins = preferences[ENABLED_PLUGINS]?.let {
                    runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet())
                } ?: emptySet(),
                pluginConfigs = preferences[PLUGIN_CONFIGS]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
                } ?: emptyMap(),
                pluginMarketRepo = preferences[PLUGIN_MARKET_REPO]
                    ?.takeIf { it != Settings.LEGACY_BROKEN_MARKET_REPO }
                    ?: Settings.DEFAULT_PLUGIN_MARKET_REPO,
                githubToken = preferences[GITHUB_TOKEN] ?: "",
                tavernImportedKeys = preferences[TAVERN_IMPORTED_KEYS] ?: emptySet(),
                builtinMakerSkillCleanupDone = preferences[BUILTIN_MAKER_SKILL_CLEANUP] ?: false,
            )
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id }) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        // 全量 JSON 解码链与 PebbleEngine 首次构建移出主线程：DataStore 首次发射时
        // settings 包含 providers/assistants/tts 等大 JSON，解码 + templateCache 失效
        // 在 Default 线程执行，避免冷启动首帧在 Main 线程做全量 JSON 解析
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }
        .flowOn(Dispatchers.Default)

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        settingsFlow.value = settings
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
            preferences[NETWORK_SETTING] = JsonInstant.encodeToString(settings.networkSetting)

            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            preferences[FAST_MODEL] = settings.fastModelId.toString()
            preferences[FAST_MODEL_REASONING_LEVEL] = settings.fastModelReasoningLevel.name
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[VIDEO_GENERATION_MODEL] = settings.videoGenerationModelId.toString()
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[GLOBAL_PROMPT] = settings.globalPrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId?.toString() ?: ""
            preferences[OCR_PROMPT] = settings.ocrPrompt
            preferences[OCR_ENABLED] = settings.ocrEnabled
            preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
            preferences[COMPRESS_PROMPT] = settings.compressPrompt
            settings.memoryModelId?.let {
                preferences[MEMORY_MODEL] = it.toString()
            } ?: preferences.remove(MEMORY_MODEL)
            settings.selfHostedModelId?.let {
                preferences[SELF_HOSTED_MODEL] = it.toString()
            } ?: preferences.remove(SELF_HOSTED_MODEL)
            preferences[IMAGE_GENERATION_PROMPT] = settings.imageGenerationPrompt
            preferences[MEMORY_PROMPT] = settings.memoryPrompt
            preferences[SELF_HOSTED_PROMPT] = settings.selfHostedPrompt
            preferences[AUTO_COMPRESS_ENABLED] = settings.autoCompressEnabled
            preferences[AUTO_COMPRESS_CONTEXT_PERCENT] = settings.autoCompressContextPercent
            preferences[AUTO_COMPRESS_MAX_MODE] = settings.autoCompressMaxMode
            preferences[AUTO_RECONNECT_ENABLED] = settings.autoReconnectEnabled
            preferences[AUTO_RECONNECT_MAX_RETRIES] = settings.autoReconnectMaxRetries
            preferences[MULTI_ROUTE_CONCURRENT] = settings.multiRouteConcurrent
            preferences[MAX_CONCURRENT_ROUTES] = settings.maxConcurrentRoutes
            preferences[GLOBAL_TOOL_SCRIPTS] = settings.globalToolScripts
            preferences[GLOBAL_TOOL_ACCESSIBILITY] = settings.globalToolAccessibility
            preferences[GLOBAL_TOOL_POWER_MANAGEMENT] = settings.globalToolPowerManagement
            preferences[GLOBAL_TOOL_TERMUX] = settings.globalToolTermux
            preferences[RECALL_SEGMENTED] = settings.recallSegmented
            preferences[RECALL_BOUNDARY_PUNCTUATION] = settings.recallBoundaryPunctuation
            preferences[RECALL_ROLLBACK_ENABLED] = settings.recallRollbackEnabled
            preferences[RECALL_INFORMED_AI] = settings.recallInformedAi
            preferences[FLOATING_BUBBLE_ENABLED] = settings.floatingBubbleEnabled
            preferences[FLOATING_BUBBLE_COLOR] = settings.floatingBubbleColor.toString()
            preferences[FLOATING_BUBBLE_SIZE] = settings.floatingBubbleSize
            preferences[FLOATING_BUBBLE_EXPAND_WIDTH] = settings.floatingBubbleExpandWidth
            preferences[FLOATING_BUBBLE_EXPAND_HEIGHT] = settings.floatingBubbleExpandHeight
            preferences[FLOATING_BUBBLE_SHOW_TODO_TAB] = settings.floatingBubbleShowTodoTab
            preferences[FLOATING_BUBBLE_SHOW_LIVE_TAB] = settings.floatingBubbleShowLiveTab
            preferences[FLOATING_BUBBLE_AUTO_HIDE_SECONDS] = settings.floatingBubbleAutoHideSeconds

            preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)

            settings.globalProxy?.let {
                preferences[GLOBAL_PROXY] = JsonInstant.encodeToString(it)
            } ?: preferences.remove(GLOBAL_PROXY)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, settings.searchServices.size - 1)

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            settings.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)
            preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
            preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
            settings.selectedASRProviderId?.let {
                preferences[SELECTED_ASR_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_ASR_PROVIDER)
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[LAUNCH_COUNT] = settings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            preferences[DISPLAY_SCALE_MODE] = settings.displayScaleMode
            preferences[DISPLAY_SCALE_DENSITY_DPI] = settings.displayScaleDensityDpi
            preferences[AUTO_APPROVE_TOOLS] = settings.autoApproveTools
            preferences[AUTONOMOUS_EXECUTION_ENABLED] = settings.autonomousExecutionEnabled
            preferences[KEEP_ALIVE_ENABLED] = settings.keepAliveEnabled
            preferences[ENABLED_PLUGINS] = JsonInstant.encodeToString(settings.enabledPlugins)
            preferences[PLUGIN_CONFIGS] = JsonInstant.encodeToString(settings.pluginConfigs)
            preferences[PLUGIN_MARKET_REPO] = settings.pluginMarketRepo
            preferences[GITHUB_TOKEN] = settings.githubToken
            preferences[TAVERN_IMPORTED_KEYS] = settings.tavernImportedKeys
            preferences[BUILTIN_MAKER_SKILL_CLEANUP] = settings.builtinMakerSkillCleanupDone
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val videoGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val globalPrompt: String = "",
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid? = null,
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val ocrEnabled: Boolean = true,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    // 全局网络代理（Provider 级代理优先，未设置时回落全局）
    val globalProxy: me.rerere.ai.provider.ProxyConfig? = null,
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
    // scope-recall 记忆引擎配置
    val memoryRecallLimit: Int = 8,
    val memoryRetrievalMode: String = "hybrid",
    val memoryMinScore: Float = 0.05f,
    val memoryJournalEnabled: Boolean = false,
    // 记忆系统专用模型（摘要/嵌入等）；null 表示随对话默认模型
    val memoryModelId: Uuid? = null,
    // 自托管模型；null 表示未选择
    val selfHostedModelId: Uuid? = null,
    // 各专用模型的提示词
    val imageGenerationPrompt: String = DEFAULT_IMAGE_GENERATION_PROMPT,
    val memoryPrompt: String = DEFAULT_MEMORY_PROMPT,
    val selfHostedPrompt: String = DEFAULT_SELF_HOSTED_PROMPT,
    // 自动压缩：上下文 token 估算达到阈值时自动压缩并继续生成。
    // 阈值按当前模型上下文窗口的百分比动态计算；
    // Max 模式先按窗口 ×3 放大基准（长任务聚合场景）再应用百分比
    val autoCompressEnabled: Boolean = false,
    val autoCompressContextPercent: Int = 60,
    val autoCompressMaxMode: Boolean = false,
    // 自动重连：生成过程中遇到网络断开时自动重试直到收到响应
    val autoReconnectEnabled: Boolean = false,
    val autoReconnectMaxRetries: Int = 3,
    // 多线路并发：请求自动探测同名模型的多条 provider 线路，并发竞速 + 故障转移
    val multiRouteConcurrent: Boolean = false,
    // 多线路并发最大线路数：限制同时竞速的备用线路数量（1-5，默认3）
    val maxConcurrentRoutes: Int = 3,
    // AI 全能控制：全局脚本工具开关（对所有助手生效，与助手自身的 localTools 取并集）
    val globalToolScripts: Boolean = false,
    // AI 全能控制：全局无障碍控制开关（对所有助手生效，与助手自身的 localTools 取并集）
    val globalToolAccessibility: Boolean = false,
    // AI 全能控制：全局电源管理开关（对所有助手生效，与助手自身的 localTools 取并集）
    val globalToolPowerManagement: Boolean = false,
    // AI 全能控制：全局 Termux 桥接开关（对所有助手生效，与助手自身的 localTools 取并集）
    val globalToolTermux: Boolean = false,
    // 消息撤回：范围（true=分段截断，false=整条）、边界标点、副作用回滚、撤回后告知 AI
    val recallSegmented: Boolean = false,
    val recallBoundaryPunctuation: String = "。！？～",
    val recallRollbackEnabled: Boolean = true,
    val recallInformedAi: Boolean = true,
    // 屏幕显示缩放：把整个 app 按目标密度（densityDpi）渲染，手机屏幕上呈现平板布局。
    // mode: 0=恢复（跟随设备） 1=平板预设 2=自定义密度
    val displayScaleMode: Int = 0,
    val displayScaleDensityDpi: Int = 240,
    // 托管工具自动审批：开启后工作区/系统等需审批的工具调用自动通过（ask_user 仍需人工）
    // 默认开启：AI 执行任务时工具调用自动放行，不中途停下等待审批（对标全自主执行）
    val autoApproveTools: Boolean = true,
    // 自主执行引导：开启后生成系统提示注入"连续调用工具直到任务完成"指令，
    // 默认开启：AI 收到任务后持续调用工具执行到底，不中途停下汇报或询问（对标全自主执行）
    val autonomousExecutionEnabled: Boolean = true,
    // 插件市场：已启用插件 id 集合；插件启用后注入 systemPrompt 并显示快捷操作
    val enabledPlugins: Set<String> = emptySet(),
    // 插件配置：声明了 config schema（plugin.json "config"）的插件，用户编辑后的配置对象 JSON 文本（按插件 id）。
    // 写入后 settingsFlow 立即发射，ChatService 下一轮生成与 Hook 链实时读取 → 配置热生效，无需重启会话。
    val pluginConfigs: Map<String, String> = emptyMap(),
    // 插件市场索引仓库（owner/repo，根目录放 plugins.json）
    val pluginMarketRepo: String = DEFAULT_PLUGIN_MARKET_REPO,
    // 内置「插件包制作技能」默认启用残留的一次性清理标记（旧版本自动启用过，新版本仅预置不启用）
    val builtinMakerSkillCleanupDone: Boolean = false,
    // GitHub 访问令牌（PAT），用于上传插件到自己的仓库
    val githubToken: String = "",
    // 酒馆角色卡导入记录（已注册为本地助手的卡片标识 name@来源，避免重复导入）
    val tavernImportedKeys: Set<String> = emptySet(),
    // 后台保活常驻通知：进应用即在消息栏常驻显示"正在运行中"
    val keepAliveEnabled: Boolean = true,
    // 悬浮球：系统级悬浮窗，可拖动、半隐藏，点击回到软件
    val floatingBubbleEnabled: Boolean = false,
    val floatingBubbleColor: Long = 0xFF4F8EF7,
    val floatingBubbleSize: Int = 48,
    // 悬浮球展开窗口：宽度/高度（dp），以及待办/实时输出标签开关
    val floatingBubbleExpandWidth: Int = 300,
    val floatingBubbleExpandHeight: Int = 420,
    val floatingBubbleShowTodoTab: Boolean = true,
    val floatingBubbleShowLiveTab: Boolean = true,
    // 无操作 N 秒后悬浮球自动贴边弱化（0 = 禁用）
    val floatingBubbleAutoHideSeconds: Int = 15,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)

        /** 默认插件市场索引仓库 */
        const val DEFAULT_PLUGIN_MARKET_REPO = "995fuviokd-crypto/plugin-market"

        /** 旧版默认市场仓库（不存在，历史版本可能已持久化，读取时忽略回退到新默认值） */
        const val LEGACY_BROKEN_MARKET_REPO = "rikkahub/plugin-market"
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
