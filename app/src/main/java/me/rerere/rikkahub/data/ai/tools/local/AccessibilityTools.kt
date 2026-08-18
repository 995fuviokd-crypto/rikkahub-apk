package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.accessibilityservice.AccessibilityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.accessibility.AccessibilityBridge
import me.rerere.rikkahub.utils.hasIgnoreBatteryOptimizationsPermission
import me.rerere.rikkahub.utils.openBatteryOptimizationSettings

private const val ACTION_BACK = "back"
private const val ACTION_HOME = "home"
private const val ACTION_NOTIFICATIONS = "notifications"
private const val ACTION_RECENTS = "recents"

/** 无障碍工具: 通过无障碍服务操控手机(打开应用/点击/导航), 默认执行无需审批 */
internal fun buildAccessibilityTools(context: Context): List<Tool> = listOf(
    buildOpenAppTool(context),
    buildSetVolumeTool(context),
    buildClickTextTool(),
    buildTapTool(),
    buildNavigateTool(),
)

/** 电量管理工具: 查询电池状态 / 引导解除电量限制 */
internal fun buildPowerTools(context: Context): List<Tool> = listOf(
    buildGetPowerInfoTool(context),
    buildIgnoreBatteryOptimizationTool(context),
)

private fun buildOpenAppTool(context: Context): Tool = Tool(
    name = "open_app",
    description = """
        Open an app on the phone by its package name or app label, e.g. open_app(package="com.tencent.mm") to open WeChat.
        Returns the matched package name or an error if the app is not installed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package name of the app to open (e.g. com.tencent.mm)")
                })
                put("app", buildJsonObject {
                    put("type", "string")
                    put("description", "App label to fuzzy-match when package is not provided (e.g. WeChat)")
                })
            },
            required = listOf(),
        )
    },
    execute = {
        val params = it.jsonObject
        val pkg = params.string("package")
        val label = params.string("app")
        require(pkg != null || label != null) { "Provide either package or app" }
        val resolved = pkg?.takeIf { it.isNotBlank() } ?: findLaunchablePackage(context, label!!)
        require(resolved != null) { "App not found: ${label ?: pkg}" }
        val launch = context.packageManager.getLaunchIntentForPackage(resolved)
            ?: error("App has no launcher activity: $resolved")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("opened", resolved)
        }.toString()))
    }
)

private fun buildSetVolumeTool(context: Context): Tool = Tool(
    name = "set_volume",
    description = """
        Adjust the device volume. Stream can be media, ring, alarm or notification.
        direction: up, down or mute (toggle mute). For up/down, amount defaults to 1 step.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("media")); add(JsonPrimitive("ring")); add(JsonPrimitive("alarm")); add(JsonPrimitive("notification"))
                    })
                    put("description", "Audio stream, defaults to media")
                })
                put("direction", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("up")); add(JsonPrimitive("down")); add(JsonPrimitive("mute"))
                    })
                    put("description", "Volume direction, defaults to up")
                })
                put("amount", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of steps for up/down, defaults to 1")
                })
            },
            required = listOf("direction"),
        )
    },
    execute = {
        val params = it.jsonObject
        val streamName = params.string("stream") ?: "media"
        val direction = params.string("direction") ?: "up"
        val amount = (params["amount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamName) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        when (direction) {
            "up" -> repeat(amount) { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0) }
            "down" -> repeat(amount) { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0) }
            "mute" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        }
        val level = audioManager.getStreamVolume(stream)
        val max = audioManager.getStreamMaxVolume(stream)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("stream", streamName)
            put("direction", direction)
            put("level", level)
            put("max", max)
        }.toString()))
    }
)

private fun buildClickTextTool(): Tool = Tool(
    name = "accessibility_click_text",
    description = """
        Click an element on the current screen that contains the given text, using the accessibility service.
        Requires the accessibility service to be enabled. Fails if no matching element is found.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text (or part of it) shown on the screen element to click")
                })
            },
            required = listOf("text"),
        )
    },
    execute = {
        val text = it.jsonObject.string("text") ?: error("text is required")
        val ok = AccessibilityBridge.requireService().clickByText(text)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("clicked", ok)
            put("text", text)
        }.toString()))
    }
)

private fun buildTapTool(): Tool = Tool(
    name = "accessibility_tap",
    description = """
        Perform a gesture on the current screen using the accessibility service: tap, long-press, or swipe.
        Coordinates are in screen pixels (x from left, y from top). For swipe provide x1/y1 (start) and x2/y2 (end).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("tap")); add(JsonPrimitive("long_press")); add(JsonPrimitive("swipe"))
                    })
                    put("description", "Gesture type, defaults to tap")
                })
                put("x", buildJsonObject {
                    put("type", "integer")
                    put("description", "X coordinate for tap/long_press, or start X for swipe")
                })
                put("y", buildJsonObject {
                    put("type", "integer")
                    put("description", "Y coordinate for tap/long_press, or start Y for swipe")
                })
                put("x2", buildJsonObject {
                    put("type", "integer")
                    put("description", "End X coordinate for swipe")
                })
                put("y2", buildJsonObject {
                    put("type", "integer")
                    put("description", "End Y coordinate for swipe")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Swipe duration in ms, defaults to 300")
                })
            },
            required = listOf("action", "x", "y"),
        )
    },
    execute = {
        val params = it.jsonObject
        val service = AccessibilityBridge.requireService()
        val action = params.string("action") ?: "tap"
        val x = params["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("x is required")
        val y = params["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("y is required")
        val ok = when (action) {
            "long_press" -> service.longPress(x, y)
            "swipe" -> {
                val x2 = params["x2"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("x2 is required")
                val y2 = params["y2"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("y2 is required")
                val duration = (params["duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 300L).coerceIn(50L, 2000L)
                service.swipe(x, y, x2, y2, duration)
            }
            else -> service.tap(x, y)
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("performed", ok)
            put("action", action)
        }.toString()))
    }
)

private fun buildNavigateTool(): Tool = Tool(
    name = "accessibility_navigate",
    description = """
        Perform a system navigation action on the current screen using the accessibility service.
        action: back, home, notifications, or recents.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("back")); add(JsonPrimitive("home")); add(JsonPrimitive("notifications")); add(JsonPrimitive("recents"))
                    })
                    put("description", "Navigation action to perform")
                })
            },
            required = listOf("action"),
        )
    },
    execute = {
        val service = AccessibilityBridge.requireService()
        val action = it.jsonObject.string("action") ?: error("action is required")
        val globalAction = when (action) {
            ACTION_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            ACTION_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            ACTION_NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            ACTION_RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> error("Unknown navigation action: $action")
        }
        val ok = service.performGlobalNav(globalAction)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("performed", ok)
            put("action", action)
        }.toString()))
    }
)

private fun buildGetPowerInfoTool(context: Context): Tool = Tool(
    name = "get_power_info",
    description = """
        Get the current battery status and whether the app is exempted from battery optimizations.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        listOf(UIMessagePart.Text(buildJsonObject {
            put("battery_level", level)
            put("charging", charging)
            put("ignoring_battery_optimizations", context.hasIgnoreBatteryOptimizationsPermission())
        }.toString()))
    }
)

private fun buildIgnoreBatteryOptimizationTool(context: Context): Tool = Tool(
    name = "ignore_battery_optimizations",
    description = """
        Open the system battery optimization settings to let the user exempt this app from battery restrictions,
        improving background keep-alive. Returns the current exemption status.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        if (!context.hasIgnoreBatteryOptimizationsPermission()) {
            context.openBatteryOptimizationSettings()
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ignoring_battery_optimizations", context.hasIgnoreBatteryOptimizationsPermission())
            put("note", "After granting, the app will be more resistant to background killing")
        }.toString()))
    }
)

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

/** 根据应用名模糊匹配已安装的可启动应用, 返回 packageName */
private fun findLaunchablePackage(context: Context, label: String): String? {
    val query = label.trim()
    if (query.isBlank()) return null
    val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    val resolved = context.packageManager.queryIntentActivities(launcherIntent, 0)
    // 先精确匹配 label, 再按包含匹配(取最短名字的)
    val exact = resolved.firstOrNull {
        it.loadLabel(context.packageManager).toString().equals(query, ignoreCase = true)
    }
    if (exact != null) return exact.activityInfo.packageName
    return resolved
        .mapNotNull { ai ->
            val name = ai.loadLabel(context.packageManager).toString()
            if (name.contains(query, ignoreCase = true)) ai.activityInfo.packageName to name else null
        }
        .minByOrNull { it.second.length }
        ?.first
}
