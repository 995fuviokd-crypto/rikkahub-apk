package me.rerere.rikkahub.data.plugin

import android.webkit.JavascriptInterface
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.script.ScriptRuntime
import java.io.File

/**
 * 注入到插件 webview 页面（对象名 AndroidPlugin）的 JS bridge。
 *
 * 提供给插件面板 HTML 的能力：
 * - runTool(toolName, argsJson)：调用插件 脚本导出的工具，与聊天内 __scriptToolsCall 同路径
 * - readData / writeData / deleteData：读写插件数据沙箱目录 filesDir/script-data/<pluginId>/（兼容旧版 operit-data/）
 *   （与脚本内 Tools.Files 的根目录一致，面板与工具读写同一份数据）
 *
 * 所有方法同步返回 JSON 字符串，JS 侧直接 JSON.parse。
 */
class PluginJsBridge(
    private val pluginId: String,
    private val pluginManager: PluginManager,
    private val scriptRuntime: ScriptRuntime,
) {
    companion object {
        private const val TAG = "PluginJsBridge"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @JavascriptInterface
    fun runTool(toolName: String, argsJson: String): String {
        return runCatching {
            val pluginDir = pluginManager.getPluginDir(pluginId)
            val result = scriptRuntime.runTool(pluginDir, pluginId, toolName, argsJson)
            encode(result.ok, result.message, result.data)
        }.getOrElse { e ->
            Log.w(TAG, "runTool failed: $pluginId/$toolName", e)
            encode(false, e.message ?: "脚本执行异常", null)
        }
    }

    @JavascriptInterface
    fun readData(path: String): String {
        val file = resolveData(path) ?: return encode(false, "路径无效", null)
        if (!file.isFile) return encode(true, "ok", json.parseToJsonElement("\"\""))
        return runCatching {
            encode(true, "ok", JsonPrimitive(file.readText()))
        }.getOrElse { e -> encode(false, e.message ?: "读取失败", null) }
    }

    @JavascriptInterface
    fun writeData(path: String, content: String): String {
        val file = resolveData(path) ?: return encode(false, "路径无效", null)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            encode(true, "ok", null)
        }.getOrElse { e -> encode(false, e.message ?: "写入失败", null) }
    }

    @JavascriptInterface
    fun deleteData(path: String): String {
        val file = resolveData(path) ?: return encode(false, "路径无效", null)
        return runCatching {
            if (file.isFile) {
                file.delete()
                encode(true, "已删除", null)
            } else {
                encode(true, "文件不存在", null)
            }
        }.getOrElse { e -> encode(false, e.message ?: "删除失败", null) }
    }

    private fun dataDir(): File = scriptRuntime.dataDir(pluginId)

    private fun resolveData(path: String): File? {
        if (path.isBlank()) return null
        val clean = path.replace('\\', '/').trimStart('/').trim()
        if (clean.isEmpty() || clean.contains("..")) return null
        val root = dataDir()
        val file = File(root, clean)
        return if (file.canonicalPath.startsWith(root.canonicalPath)) file else null
    }

    private fun encode(ok: Boolean, message: String, data: JsonElement?): String {
        return buildJsonObject {
            put("ok", ok)
            put("message", message)
            if (data != null) put("data", data)
        }.toString()
    }
}
