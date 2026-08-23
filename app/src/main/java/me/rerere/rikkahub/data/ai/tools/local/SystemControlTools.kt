package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * AI 全能控制工具组：让 AI 可以读取/修改软件内一切设置、开关、模型、助手、插件与提示词。
 * 写入型工具统一 needsApproval=true，配合"托管工具自动审批"开关实现全自主执行。
 */
class SystemControlTools(
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
) {
    private val currentSettings: Settings
        get() = settingsStore.settingsFlow.value

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject.float(key: String): Float? =
        this[key]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()

    val tools: List<Tool> by lazy {
        listOf(
            buildSettingsGet(),
            buildSettingsSet(),
            buildModelsList(),
            buildModelSwitch(),
            buildAssistantsList(),
            buildAssistantSwitch(),
            buildPluginsList(),
            buildPluginsSet(),
        )
    }

    // ---------------------------------------------------------------------
    // 1. 读取全部设置
    // ---------------------------------------------------------------------

    private fun buildSettingsGet(): Tool = Tool(
        name = "system_settings_get",
        description = """
            Read the current value of RikkaHub app settings. Returns a JSON object whose
            keys are every AI-manageable setting key with its current value. Use
            system_settings_set to change any of these. Keys are stable identifiers.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("keys", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Optional list of setting keys to read (empty = read all)")
                    })
                },
                required = emptyList(),
            )
        },
        execute = { params ->
            val requested = params.jsonObject["keys"]?.let { el ->
                runCatching {
                    (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    }
                }.getOrNull()
            } ?: emptyList()
            val snapshot = settingsSnapshot(currentSettings)
            val filtered = if (requested.isEmpty()) {
                snapshot
            } else {
                JsonObject(snapshot.filterKeys { it in requested })
            }
            listOf(UIMessagePart.Text(filtered.toString()))
        }
    )

    // ---------------------------------------------------------------------
    // 2. 批量修改设置
    // ---------------------------------------------------------------------

    private fun buildSettingsSet(): Tool = Tool(
        name = "system_settings_set",
        description = """
            Update one or more RikkaHub app settings in a single call. Provide an object of
            {settingKey: newValue}. All values are strings: booleans "true"/"false", integers as
            digit strings, floats as decimal strings. Unknown or mistyped keys are reported back.
            Use system_settings_get to list valid keys first.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("updates", buildJsonObject {
                        put("type", "object")
                        put("description", "Map of setting key -> new string value")
                    })
                },
                required = listOf("updates"),
            )
        },
        needsApproval = { true },
        execute = { params ->
            val updates = params.jsonObject["updates"]
                ?.let { it as? JsonObject }
                ?: error("updates must be a JSON object of key -> value")
            val baseline = if (currentSettings.init) Settings.dummy() else currentSettings
            var settings = baseline
            val results = buildJsonObject {
                for ((key, value) in updates) {
                    val text = value.jsonPrimitive.contentOrNull ?: ""
                    try {
                        settings = applySetting(settings, key, text)
                        put(key, "ok".j())
                    } catch (e: IllegalArgumentException) {
                        put(key, ("error: ${e.message}").j())
                    }
                }
                put("applied", true.j())
            }
            if (settings != baseline) {
                settingsStore.update(settings = settings)
            }
            listOf(UIMessagePart.Text(results.toString()))
        }
    )

    // ---------------------------------------------------------------------
    // 3. 列出模型
    // ---------------------------------------------------------------------

    private fun buildModelsList(): Tool = Tool(
        name = "system_models_list",
        description = """
            List every AI model currently configured in the app, grouped by provider.
            Returns provider id/name, model id, modelId (wire name) and display name.
            Use system_model_switch to change which model is active.
        """.trimIndent().replace("\n", " "),
        execute = { _ ->
            val settings = currentSettings
            val out = buildJsonObject {
                for (provider in settings.providers) {
                    put(provider.id.toString(), buildJsonObject {
                        put("name", provider.name.j())
                        put("enabled", provider.enabled.j())
                    })
                    for (model in provider.models) {
                        put(model.id.toString(), buildJsonObject {
                            put("modelId", model.modelId.j())
                            put("displayName", model.displayName.j())
                            put("providerId", provider.id.toString().j())
                            put("providerName", provider.name.j())
                        })
                    }
                }
                put("activeAssistantId", settings.assistantId.toString().j())
                put("activeModelId", (settings.getActiveAssistant().chatModelId ?: settings.chatModelId).toString().j())
            }
            listOf(UIMessagePart.Text(out.toString()))
        }
    )

    // ---------------------------------------------------------------------
    // 4. 切换模型
    // ---------------------------------------------------------------------

    private fun buildModelSwitch(): Tool = Tool(
        name = "system_model_switch",
        description = """
            Switch the model used by an assistant (or the global default when assistant is omitted).
            Provide modelId (the model's UUID string, from system_models_list) and optionally
            assistantId. Returns the new active model info.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("modelId", buildJsonObject {
                        put("type", "string")
                        put("description", "The model UUID string from system_models_list")
                    })
                    put("assistantId", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional assistant UUID; default switches the global chat model")
                    })
                },
                required = listOf("modelId"),
            )
        },
        needsApproval = { true },
        execute = { params ->
            val modelId = Uuid.parse(params.jsonObject["modelId"]!!.jsonPrimitive.content)
            val assistantId = params.jsonObject["assistantId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            val settings = currentSettings
            val targetAssistant = settings.getAssistantById(assistantId ?: settings.assistantId)
            if (targetAssistant != null) {
                settingsStore.updateAssistantModel(targetAssistant.id, modelId)
            } else {
                settingsStore.update(settings.copy(chatModelId = modelId))
            }
            val model = settings.providers.findModelByUuid(modelId)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("modelId", modelId.toString().j())
                put("modelIdWire", (model?.modelId ?: "?").j())
                put("displayName", (model?.displayName ?: "?").j())
                put("targetAssistantId", (targetAssistant?.id?.toString() ?: "global").j())
            }.toString()))
        }
    )

    // ---------------------------------------------------------------------
    // 5. 列出助手
    // ---------------------------------------------------------------------

    private fun buildAssistantsList(): Tool = Tool(
        name = "system_assistants_list",
        description = """
            List every assistant configured in the app with id, name and current model.
            Use system_assistant_switch to change the active assistant.
        """.trimIndent().replace("\n", " "),
        execute = { _ ->
            val out = buildJsonObject {
                currentSettings.assistants.forEach { assistant ->
                    val model = assistant.chatModelId?.let { currentSettings.providers.findModelByUuid(it) }
                    put(assistant.id.toString(), buildJsonObject {
                        put("name", assistant.name.j())
                        put("systemPrompt", assistant.systemPrompt.j())
                        put("modelId", (assistant.chatModelId?.toString() ?: "").j())
                        put("modelWire", (model?.modelId ?: "").j())
                    })
                }
                put("activeAssistantId", currentSettings.assistantId.toString().j())
            }
            listOf(UIMessagePart.Text(out.toString()))
        }
    )

    // ---------------------------------------------------------------------
    // 6. 切换助手
    // ---------------------------------------------------------------------

    private fun buildAssistantSwitch(): Tool = Tool(
        name = "system_assistant_switch",
        description = """
            Switch the active assistant. Provide the assistant UUID from system_assistants_list.
            Returns the now-active assistant name.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("assistantId", buildJsonObject {
                        put("type", "string")
                        put("description", "Assistant UUID string")
                    })
                },
                required = listOf("assistantId"),
            )
        },
        needsApproval = { true },
        execute = { params ->
            val id = Uuid.parse(params.jsonObject["assistantId"]!!.jsonPrimitive.content)
            val settings = currentSettings
            val assistant = settings.getAssistantById(id)
            if (assistant == null) {
                error("assistant not found: $id")
            }
            settingsStore.updateAssistant(id)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("assistantId", id.toString().j())
                put("name", assistant.name.j())
            }.toString()))
        }
    )

    // ---------------------------------------------------------------------
    // 7. 插件市场：查看已安装与启用状态
    // ---------------------------------------------------------------------

    private fun buildPluginsList(): Tool = Tool(
        name = "system_plugins_list",
        description = """
            List installed plugins (from the plugin market) with id, title, type and enabled state.
            Use system_plugins_set to enable or disable them.
        """.trimIndent().replace("\n", " "),
        execute = { _ ->
            val settings = currentSettings
            val out = buildJsonObject {
                for (plugin in pluginManager.listPlugins()) {
                    val info = plugin.info
                    put(plugin.id, buildJsonObject {
                        put("title", (info?.name ?: "(broken)").j())
                        put("type", (info?.type ?: "corrupted").j())
                        put("version", (info?.version ?: "").j())
                        put("enabled", settings.enabledPlugins.contains(plugin.id).j())
                    })
                }
                put("marketRepo", settings.pluginMarketRepo.j())
            }
            listOf(UIMessagePart.Text(out.toString()))
        }
    )

    // ---------------------------------------------------------------------
    // 8. 插件市场：启用/禁用
    // ---------------------------------------------------------------------

    private fun buildPluginsSet(): Tool = Tool(
        name = "system_plugins_set",
        description = """
            Enable or disable installed plugins. Provide pluginId and enabled ("true" or "false").
            Disabling does not uninstall; it stops prompt injection and action exposure.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("pluginId", buildJsonObject {
                        put("type", "string")
                        put("description", "Plugin id from system_plugins_list")
                    })
                    put("enabled", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("true"))
                            add(JsonPrimitive("false"))
                        })
                        put("description", "\"true\" to enable, \"false\" to disable")
                    })
                },
                required = listOf("pluginId", "enabled"),
            )
        },
        needsApproval = { true },
        execute = { params ->
            val pluginId = params.jsonObject["pluginId"]!!.jsonPrimitive.content
            val enable = params.jsonObject["enabled"]!!.jsonPrimitive.content == "true"
            val settings = currentSettings
            val exists = pluginManager.getInstalled(pluginId) != null
            if (!exists) {
                error("plugin not installed: $pluginId")
            }
            val next = if (enable) settings.enabledPlugins + pluginId else settings.enabledPlugins - pluginId
            settingsStore.update(settings.copy(enabledPlugins = next))
            listOf(UIMessagePart.Text(buildJsonObject {
                put("pluginId", pluginId.j())
                put("enabled", enable.j())
            }.toString()))
        }
    )

    // ---------------------------------------------------------------------
    // 可管理的设置白名单（key -> 读取/写入映射）
    // ---------------------------------------------------------------------

    }

private fun String.j(): JsonPrimitive = JsonPrimitive(this)

private fun Boolean.j(): JsonPrimitive = JsonPrimitive(this.toString())

private fun Int.j(): JsonPrimitive = JsonPrimitive(this.toString())

internal fun settingsSnapshot(settings: Settings): JsonObject = buildJsonObject {
    put("developerMode", settings.developerMode.j())
    put("dynamicColor", settings.dynamicColor.j())
    put("themeId", settings.themeId.j())
    put("displayScaleMode", settings.displayScaleMode.toString().j())
    put("displayScaleDensityDpi", settings.displayScaleDensityDpi.toString().j())
    put("autoApproveTools", settings.autoApproveTools.j())
    put("autonomousExecutionEnabled", settings.autonomousExecutionEnabled.j())
    put("keepAliveEnabled", settings.keepAliveEnabled.j())
    put("floatingBubbleEnabled", settings.floatingBubbleEnabled.j())
    put("floatingBubbleSize", settings.floatingBubbleSize.toString().j())
    put("floatingBubbleExpandWidth", settings.floatingBubbleExpandWidth.toString().j())
    put("floatingBubbleExpandHeight", settings.floatingBubbleExpandHeight.toString().j())
    put("floatingBubbleShowTodoTab", settings.floatingBubbleShowTodoTab.j())
    put("floatingBubbleShowLiveTab", settings.floatingBubbleShowLiveTab.j())
    put("autoCompressEnabled", settings.autoCompressEnabled.j())
    put("autoCompressThresholdTokens", settings.autoCompressThresholdTokens.toString().j())
    put("autoCompressKeepRecent", settings.autoCompressKeepRecent.toString().j())
    put("autoReconnectEnabled", settings.autoReconnectEnabled.j())
    put("autoReconnectMaxRetries", settings.autoReconnectMaxRetries.toString().j())
    put("multiRouteConcurrent", settings.multiRouteConcurrent.j())
    put("recallSegmented", settings.recallSegmented.j())
    put("recallBoundaryPunctuation", settings.recallBoundaryPunctuation.j())
    put("recallRollbackEnabled", settings.recallRollbackEnabled.j())
    put("recallInformedAi", settings.recallInformedAi.j())
    put("enableSuggestion", settings.enableSuggestion.j())
    put("webServerEnabled", settings.webServerEnabled.j())
    put("webServerPort", settings.webServerPort.toString().j())
    put("webServerJwtEnabled", settings.webServerJwtEnabled.j())
    put("webServerLocalhostOnly", settings.webServerLocalhostOnly.j())
    put("memoryJournalEnabled", settings.memoryJournalEnabled.j())
    put("memoryRecallLimit", settings.memoryRecallLimit.toString().j())
    put("memoryRetrievalMode", settings.memoryRetrievalMode.j())
    put("memoryMinScore", settings.memoryMinScore.toString().j())
    put("ocrEnabled", settings.ocrEnabled.j())
    put("globalPrompt", settings.globalPrompt.j())
    put("titlePrompt", settings.titlePrompt.j())
    put("suggestionPrompt", settings.suggestionPrompt.j())
    put("translatePrompt", settings.translatePrompt.j())
    put("ocrPrompt", settings.ocrPrompt.j())
    put("compressPrompt", settings.compressPrompt.j())
    put("imageGenerationPrompt", settings.imageGenerationPrompt.j())
    put("memoryPrompt", settings.memoryPrompt.j())
    put("selfHostedPrompt", settings.selfHostedPrompt.j())
    put("enabledPlugins", settings.enabledPlugins.joinToString(",").j())
}

internal fun applySetting(settings: Settings, key: String, value: String): Settings {
    fun int(): Int = value.toIntOrNull()
        ?: throw IllegalArgumentException("expected integer, got: $value")

    fun float(): Float = value.toFloatOrNull()
        ?: throw IllegalArgumentException("expected number, got: $value")

    fun bool(): Boolean = value.toBooleanStrictOrNull()
        ?: throw IllegalArgumentException("expected true/false, got: $value")

    return when (key) {
        "developerMode" -> settings.copy(developerMode = bool())
        "dynamicColor" -> settings.copy(dynamicColor = bool())
        "themeId" -> settings.copy(themeId = value)
        "displayScaleMode" -> settings.copy(displayScaleMode = int())
        "displayScaleDensityDpi" -> settings.copy(displayScaleDensityDpi = int())
        "autoApproveTools" -> settings.copy(autoApproveTools = bool())
        "autonomousExecutionEnabled" -> settings.copy(autonomousExecutionEnabled = bool())
        "keepAliveEnabled" -> settings.copy(keepAliveEnabled = bool())
        "floatingBubbleEnabled" -> settings.copy(floatingBubbleEnabled = bool())
        "floatingBubbleSize" -> settings.copy(floatingBubbleSize = int())
        "floatingBubbleExpandWidth" -> settings.copy(floatingBubbleExpandWidth = int())
        "floatingBubbleExpandHeight" -> settings.copy(floatingBubbleExpandHeight = int())
        "floatingBubbleShowTodoTab" -> settings.copy(floatingBubbleShowTodoTab = bool())
        "floatingBubbleShowLiveTab" -> settings.copy(floatingBubbleShowLiveTab = bool())
        "autoCompressEnabled" -> settings.copy(autoCompressEnabled = bool())
        "autoCompressThresholdTokens" -> settings.copy(autoCompressThresholdTokens = int())
        "autoCompressKeepRecent" -> settings.copy(autoCompressKeepRecent = int())
        "autoReconnectEnabled" -> settings.copy(autoReconnectEnabled = bool())
        "autoReconnectMaxRetries" -> settings.copy(autoReconnectMaxRetries = int())
        "multiRouteConcurrent" -> settings.copy(multiRouteConcurrent = bool())
        "recallSegmented" -> settings.copy(recallSegmented = bool())
        "recallBoundaryPunctuation" -> settings.copy(recallBoundaryPunctuation = value)
        "recallRollbackEnabled" -> settings.copy(recallRollbackEnabled = bool())
        "recallInformedAi" -> settings.copy(recallInformedAi = bool())
        "enableSuggestion" -> settings.copy(enableSuggestion = bool())
        "webServerEnabled" -> settings.copy(webServerEnabled = bool())
        "webServerPort" -> settings.copy(webServerPort = int())
        "webServerJwtEnabled" -> settings.copy(webServerJwtEnabled = bool())
        "webServerLocalhostOnly" -> settings.copy(webServerLocalhostOnly = bool())
        "memoryJournalEnabled" -> settings.copy(memoryJournalEnabled = bool())
        "memoryRecallLimit" -> settings.copy(memoryRecallLimit = int())
        "memoryRetrievalMode" -> settings.copy(memoryRetrievalMode = value)
        "memoryMinScore" -> settings.copy(memoryMinScore = float())
        "ocrEnabled" -> settings.copy(ocrEnabled = bool())
        "globalPrompt" -> settings.copy(globalPrompt = value)
        "titlePrompt" -> settings.copy(titlePrompt = value)
        "suggestionPrompt" -> settings.copy(suggestionPrompt = value)
        "translatePrompt" -> settings.copy(translatePrompt = value)
        "ocrPrompt" -> settings.copy(ocrPrompt = value)
        "compressPrompt" -> settings.copy(compressPrompt = value)
        "imageGenerationPrompt" -> settings.copy(imageGenerationPrompt = value)
        "memoryPrompt" -> settings.copy(memoryPrompt = value)
        "selfHostedPrompt" -> settings.copy(selfHostedPrompt = value)
        else -> throw IllegalArgumentException("unknown setting key: $key")
    }
}

private fun List<me.rerere.ai.provider.ProviderSetting>.findModelByUuid(uuid: Uuid): me.rerere.ai.provider.Model? {
    for (provider in this) {
        for (model in provider.models) {
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

private fun Settings.getActiveAssistant() = this.assistants.firstOrNull { it.id == assistantId } ?: this.assistants.first()