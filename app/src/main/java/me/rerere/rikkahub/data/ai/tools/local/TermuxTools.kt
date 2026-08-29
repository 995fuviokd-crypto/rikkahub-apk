package me.rerere.rikkahub.data.ai.tools.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/**
 * Termux 桥接工具：通过真实 Termux 应用的 RUN_COMMAND 接口在真实 Linux 环境执行命令。
 *
 * 前置条件（用户在 Termux 内执行一次）：
 * 1. 已安装 Termux 应用（包名 com.termux）
 * 2. 在 ~/.termux/termux.properties 里写入 allow-external-apps=true 并重启 Termux
 * 3. 执行 termux-setup-storage 授予存储权限（用于回传命令输出）
 *
 * RUN_COMMAND 是异步 fire-and-forget 接口，不直接回传 stdout；
 * 因此命令输出被重定向到 /sdcard/Download 下的临时文件，
 * 应用轮询该文件直到出现完成标记后读回结果。
 */
private const val TERMUX_PACKAGE = "com.termux"
private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val OUTPUT_DIR = "/sdcard/Download"
private const val EXIT_MARKER = "__RIKKAHUB_EXIT__:"
private const val DEFAULT_TIMEOUT_MS = 60_000L
private const val MAX_TIMEOUT_MS = 300_000L

internal fun buildTermuxTools(context: Context): List<Tool> = listOf(
    buildTermuxStatusTool(context),
    buildTermuxRunCommandTool(context),
)

private fun termuxInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
}.isSuccess

private fun termuxRunCommandResolvable(context: Context): Boolean = runCatching {
    val intent = buildRunCommandIntent(context)
    context.packageManager.resolveService(intent, 0) != null || {
        val r = context.packageManager.resolveActivity(intent, 0)
        r != null
    }()
}.getOrDefault(false)

private fun buildRunCommandIntent(context: Context): Intent = Intent(RUN_COMMAND_ACTION).apply {
    component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
}

private fun buildTermuxStatusTool(context: Context): Tool = Tool(
    name = "termux_status",
    description = "Check the Termux bridge state: whether the real Termux app is installed, " +
        "whether its RUN_COMMAND external access is enabled (allow-external-apps=true), " +
        "and the installed version. Run this first when shell commands need a real Linux environment.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { }, required = emptyList())
    },
    needsApproval = { false },
    execute = {
        val installed = termuxInstalled(context)
        val resolvable = termuxRunCommandResolvable(context)
        val version = runCatching {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0).versionName
        }.getOrNull()
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("installed", installed)
                    put("external_access_enabled", resolvable)
                    put("version", version ?: "")
                    if (!installed) {
                        put("hint", "Termux is not installed. Install it from F-Droid (or the Play Store) first.")
                    } else if (!resolvable) {
                        put(
                            "hint",
                            "Termux RUN_COMMAND access is disabled. In Termux run: " +
                                "echo allow-external-apps=true >> ~/.termux/termux.properties and restart Termux."
                        )
                    }
                }.toString()
            )
        )
    },
)

private fun buildTermuxRunCommandTool(context: Context): Tool = Tool(
    name = "termux_run_command",
    description = "Execute a shell command inside the real Termux Linux environment via the installed " +
        "Termux app (com.termux.RUN_COMMAND). The command runs in a real Linux userland with tools like " +
        "python/git/apt, and its combined output is read back to RikkaHub. " +
        "Returns the output and exit code. Requires Termux + allow-external-apps=true + storage access.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to execute, e.g. python3 -c 'print(40+2)' or 'pkg list-installed'")
                })
                put("workdir", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional working directory inside Termux (absolute path)")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional timeout in milliseconds (default 60000)")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { true },
    execute = { input ->
        val params = input.jsonObject
        val command = params.string("command").orEmpty()
        require(command.isNotBlank()) { "command is required" }
        val workdir = params.string("workdir")
        val timeoutMs = params.int("timeout_ms", DEFAULT_TIMEOUT_MS.toInt())
            .toLong().coerceIn(5_000L, MAX_TIMEOUT_MS)

        if (!termuxInstalled(context)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "Termux is not installed. Install Termux from F-Droid or the Play Store first.")
                    }.toString()
                )
            )
        }
        if (!termuxRunCommandResolvable(context)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put(
                            "error",
                            "Termux RUN_COMMAND access is disabled. In Termux run: " +
                                "echo allow-external-apps=true >> ~/.termux/termux.properties and restart Termux."
                        )
                    }.toString()
                )
            )
        }

        withContext(Dispatchers.IO) {
            val outFile = File(OUTPUT_DIR, "rikkahub_termux_${System.currentTimeMillis()}.log")
            val wrapped = buildString {
                if (!workdir.isNullOrBlank()) {
                    append("cd '").append(workdir.replace("'", "'\\''")).append("' 2>/dev/null; ")
                }
                append("{ ").append(command).append("; } 2>&1 | tee '").append(outFile.absolutePath).append(
                    "' 1>/dev/null; echo \""
                ).append(EXIT_MARKER).append("$?\" >> '").append(outFile.absolutePath).append("'")
            }
            // sh -c 透传整条命令；异步执行，输出写入临时文件后轮询读回
            val intent = buildRunCommandIntent(context).apply {
                putExtra("com.termux.RUN_COMMAND_PATH", "sh")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapped))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            val launched = try {
                context.startService(intent)
                true
            } catch (e: Exception) {
                false
            }
            if (!launched) {
                return@withContext listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", false)
                            put(
                                "error",
                                "Failed to start Termux RUN_COMMAND service. " +
                                    "Make sure allow-external-apps=true is set and Termux was opened at least once."
                            )
                        }.toString()
                    )
                )
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            var content = ""
            var exitCode: Int? = null
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                if (outFile.exists()) {
                    content = runCatching { outFile.readText() }.getOrDefault("")
                }
                val mark = content.lineSequence().lastOrNull { it.startsWith(EXIT_MARKER) }
                if (mark != null) {
                    exitCode = mark.removePrefix(EXIT_MARKER).trim().toIntOrNull()
                    break
                }
            }
            runCatching { outFile.delete() }

            val outputBody = content.lines().filterNot { it.startsWith(EXIT_MARKER) }.joinToString("\n")
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", exitCode == 0)
                        put("exit_code", exitCode ?: -1)
                        put("timed_out", exitCode == null)
                        put("output", outputBody.take(4000))
                        if (exitCode == null) {
                            put(
                                "hint",
                                "The command did not finish within the timeout. It may still be running in Termux; " +
                                    "increase timeout_ms (max 300000) or check the Termux session directly."
                            )
                        }
                    }.toString()
                )
            )
        }
    },
)

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String, default: Int = 0): Int =
    this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: default