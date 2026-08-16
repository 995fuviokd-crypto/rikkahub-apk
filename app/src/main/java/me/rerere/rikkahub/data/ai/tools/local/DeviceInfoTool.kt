package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.DeviceScreenMetrics

internal fun buildDeviceInfoTool(
    context: Context,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "get_device_info",
    description = """
        Get device information: model, manufacturer, Android version, and the current effective
        screen resolution (width x height in pixels). The reported screen resolution may be an
        override value if the user has configured a screen resolution override in preferences.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { }
        )
    },
    execute = {
        val (effectiveWidth, effectiveHeight) = DeviceScreenMetrics.getEffectiveScreenSize(context, settingsStore)
        val (realWidth, realHeight) = DeviceScreenMetrics.getRealScreenSize(context)
        val settings = settingsStore.settingsFlow.first()
        val overridden = settings.screenResolutionOverrideEnabled &&
            settings.screenResolutionOverrideWidth > 0 &&
            settings.screenResolutionOverrideHeight > 0
        val payload = buildJsonObject {
            put("device_model", Build.MODEL)
            put("device_manufacturer", Build.MANUFACTURER)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("screen_resolution", "${effectiveWidth}x$effectiveHeight")
            put("screen_resolution_width", effectiveWidth)
            put("screen_resolution_height", effectiveHeight)
            put("screen_resolution_real", "${realWidth}x$realHeight")
            put("screen_resolution_overridden", overridden)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
