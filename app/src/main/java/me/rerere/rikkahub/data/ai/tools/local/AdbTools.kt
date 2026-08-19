package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.permission.HeadlessController
import me.rerere.rikkahub.data.permission.PermissionLevel
import me.rerere.rikkahub.data.permission.PermissionManager

/**
 * ADB 级工具：依赖 Shizuku 或 root su 命令通道。
 * 高权限操作默认 needsApproval = true（受全局自动审批开关约束）。
 */
internal fun buildAdbTools(context: Context): List<Tool> {
    val controller = HeadlessController(context)
    val manager = PermissionManager(context)
    return listOf(
        buildAdbRunCommand(controller),
        buildAdbInput(controller),
        buildAdbLaunchApp(controller),
        buildAdbScreen(controller, manager),
    )
}

/** Root 工具：仅当 root 激活时注入。 */
internal fun buildRootTools(context: Context): List<Tool> {
    val controller = HeadlessController(context)
    return listOf(buildRootRunCommand(controller))
}

private fun requireChannelResult(result: me.rerere.rikkahub.data.permission.ChannelResult): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("exit_code", result.exitCode)
                put("stdout", result.stdout.take(4000))
                put("stderr", result.stderr.take(2000))
                put("success", result.success)
            }.toString()
        )
    )

private fun buildAdbRunCommand(controller: HeadlessController): Tool = Tool(
    name = "adb_run_command",
    description = "Execute an ADB-level shell command on the device (requires Shizuku or root). " +
        "For example: input keyevent 26, wm size, settings put ... Returns exit code and output.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to execute, e.g. input keyevent 26")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { true },
    execute = {
        val params = it.jsonObject
        val command = params.string("command").orEmpty()
        require(command.isNotBlank()) { "command is required" }
        requireChannelResult(controller.runCommand(command))
    },
)

private fun buildAdbInput(controller: HeadlessController): Tool = Tool(
    name = "adb_input",
    description = "Inject input events (tap/swipe/text) into the device via ADB shell. " +
        "tap: adb_input(action=\"tap\", x=100, y=200); swipe: adb_input(action=\"swipe\", x1, y1, x2, y2); " +
        "text: adb_input(action=\"text\", text=\"hello\").",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "tap / swipe / text")
                })
                put("x", buildJsonObject { put("type", "integer"); put("description", "x coordinate (tap)") })
                put("y", buildJsonObject { put("type", "integer"); put("description", "y coordinate (tap)") })
                put("x1", buildJsonObject { put("type", "integer"); put("description", "swipe start x") })
                put("y1", buildJsonObject { put("type", "integer"); put("description", "swipe start y") })
                put("x2", buildJsonObject { put("type", "integer"); put("description", "swipe end x") })
                put("y2", buildJsonObject { put("type", "integer"); put("description", "swipe end y") })
                put("duration_ms", buildJsonObject { put("type", "integer"); put("description", "swipe duration in ms") })
                put("text", buildJsonObject { put("type", "string"); put("description", "text to input") })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { true },
    execute = {
        val params = it.jsonObject
        val action = params.string("action").orEmpty()
        val result = when (action.lowercase()) {
            "tap" -> controller.inputTap(params.int("x"), params.int("y"))
            "swipe" -> controller.inputSwipe(
                params.int("x1"), params.int("y1"), params.int("x2"), params.int("y2"),
                params.int("duration_ms", 300),
            )
            "text" -> controller.inputText(params.string("text").orEmpty())
            else -> error("Unknown input action: $action (use tap/swipe/text)")
        }
        requireChannelResult(result)
    },
)

private fun buildAdbLaunchApp(controller: HeadlessController): Tool = Tool(
    name = "adb_launch_app",
    description = "Launch or stop an app by package name. " +
        "hidden=true requests off-screen window mode (root only; without root the app stays visible).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package name, e.g. com.tencent.mm")
                })
                put("hidden", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to run in off-screen (headless) mode")
                })
                put("stop", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Set true to force-stop the app instead of launching")
                })
            },
            required = listOf("package"),
        )
    },
    needsApproval = { true },
    execute = {
        val params = it.jsonObject
        val pkg = params.string("package").orEmpty()
        require(pkg.isNotBlank()) { "package is required" }
        val result = if (params.boolean("stop")) {
            controller.stopApp(pkg)
        } else {
            controller.launchApp(pkg, hidden = params.boolean("hidden"))
        }
        requireChannelResult(result)
    },
)

private fun buildAdbScreen(controller: HeadlessController, manager: PermissionManager): Tool = Tool(
    name = "adb_screen",
    description = "Query screen power status or capture a screenshot. " +
        "action=\"status\" returns wakefulness; action=\"capture\" saves to the given path.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "status / capture")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Screenshot save path (capture only)")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { true },
    execute = {
        val params = it.jsonObject
        val action = params.string("action").orEmpty()
        val result = when (action.lowercase()) {
            "status" -> controller.screenStatus()
            "capture" -> controller.screenCapture(params.string("path").orEmpty())
            else -> error("Unknown screen action: $action (use status/capture)")
        }
        requireChannelResult(result)
    },
)

private fun buildRootRunCommand(controller: HeadlessController): Tool = Tool(
    name = "root_run_command",
    description = "Execute a command with root privileges (su). Requires a rooted device. " +
        "Returns exit code and output.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Command to execute as root, e.g. pm list packages")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { true },
    execute = {
        val params = it.jsonObject
        val command = params.string("command").orEmpty()
        require(command.isNotBlank()) { "command is required" }
        requireChannelResult(controller.runCommand(command))
    },
)

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String, default: Int = 0): Int =
    this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: default

private fun JsonObject.boolean(name: String): Boolean =
    this[name]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
