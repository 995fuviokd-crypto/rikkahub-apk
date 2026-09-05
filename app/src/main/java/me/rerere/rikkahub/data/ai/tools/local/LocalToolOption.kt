package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("device_info")
    data object DeviceInfo : LocalToolOption()

    @Serializable
    @SerialName("accessibility")
    data object Accessibility : LocalToolOption()

    @Serializable
    @SerialName("power_management")
    data object PowerManagement : LocalToolOption()

    @Serializable
    @SerialName("adb")
    data object Adb : LocalToolOption()

    @Serializable
    @SerialName("root")
    data object Root : LocalToolOption()

    @Serializable
    @SerialName("scripts")
    data object Scripts : LocalToolOption()

    @Serializable
    @SerialName("system_control")
    data object SystemControl : LocalToolOption()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : LocalToolOption()

    @Serializable
    @SerialName("termux")
    data object Termux : LocalToolOption()

    @Serializable
    @SerialName("vm")
    data object Vm : LocalToolOption()
}
