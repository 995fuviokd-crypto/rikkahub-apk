package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.script.ScriptRuntime
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
    private val scriptRuntime: ScriptRuntime,
    private val systemControlTools: SystemControlTools,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val deviceInfoTool by lazy { buildDeviceInfoTool(context, settingsStore) }

    val accessibilityTools by lazy { buildAccessibilityTools(context) }

    val powerTools by lazy { buildPowerTools(context) }

    val adbTools by lazy { buildAdbTools(context) }

    val rootTools by lazy { buildRootTools(context) }

    val scriptTool by lazy { buildScriptTool(pluginManager, scriptRuntime, settingsStore) }

    val termuxTools by lazy { buildTermuxTools(context) }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.DeviceInfo)) {
            tools.add(deviceInfoTool)
        }
        if (options.contains(LocalToolOption.Accessibility)) {
            tools.addAll(accessibilityTools)
        }
        if (options.contains(LocalToolOption.PowerManagement)) {
            tools.addAll(powerTools)
        }
        if (options.contains(LocalToolOption.Adb)) {
            tools.addAll(adbTools)
        }
        if (options.contains(LocalToolOption.Root)) {
            tools.addAll(rootTools)
        }
        if (options.contains(LocalToolOption.Scripts)) {
            tools.add(scriptTool)
        }
        if (options.contains(LocalToolOption.SystemControl)) {
            tools.addAll(systemControlTools.tools)
        }
        if (options.contains(LocalToolOption.Termux)) {
            tools.addAll(termuxTools)
        }
        return tools
    }
}
