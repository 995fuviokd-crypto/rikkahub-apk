package me.rerere.rikkahub.data.operit

import android.content.Context
import android.util.Log
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.plugin.PluginManager
import java.io.File

/**
 * Operit 脚本执行器：用 QuickJS 在 App 内真实执行 Operit script / ToolPkg 中的
 * JS 工具。脚本是 CommonJS 模块（exports.xxx），依赖全局 Tools.* 运行时，本类：
 *  1. 把 Tools.* 映射为 RikkaHub 本地能力（文件沙箱、系统通知等），其余明确降级；
 *  2. 提供 CommonJS 加载器（__operitRequire）与 ToolPkg 注册 API 空实现；
 *  3. 调用导出的工具函数并返回 JSON 结果。
 */
class OperitScriptRuntime(private val context: Context) {

    companion object {
        private const val TAG = "OperitScriptRuntime"
        const val OPERIT_DIR = "operit"
        const val TOOL_MANIFEST = "toolmanifest.json"
        private const val OPERIT_DATA_ROOT = "operit-data"

        private val json = Json { ignoreUnknownKeys = true }

        /** ToolPkg 运行时注册 API 的空实现，防止 main.js 执行时崩溃 */
        private const val TOOLPKG_SHIM = """
globalThis.ToolPkg = globalThis.ToolPkg || {
    registerUiRoute: function () { return true; },
    registerNavigationEntry: function () { return true; },
    registerSettingsEntry: function () { return true; },
    _m: function () { return true; }
};
"""

        /** Tools.* 全局对象：方法统一转发到 Java 侧 __operitToolsCall(namespace, method, argsJson) */
        private const val TOOLS_SHIM = """
globalThis.Tools = {
    Files: {
        mkdir: function (p) { return __operitToolsCall("Files", "mkdir", JSON.stringify([p])); },
        exists: function (p) { return __operitToolsCall("Files", "exists", JSON.stringify([p])); },
        read: function (p) { return __operitToolsCall("Files", "read", JSON.stringify([p])); },
        readText: function (p) { return __operitToolsCall("Files", "read", JSON.stringify([p])); },
        write: function (p, c) { return __operitToolsCall("Files", "write", JSON.stringify([p, c])); },
        writeText: function (p, c) { return __operitToolsCall("Files", "write", JSON.stringify([p, c])); },
        list: function (p) { return __operitToolsCall("Files", "list", JSON.stringify([p])); },
        delete: function (p) { return __operitToolsCall("Files", "delete", JSON.stringify([p])); }
    },
    Chat: {
        listChats: function (o) { return __operitToolsCall("Chat", "listChats", JSON.stringify([o])); },
        getMessages: function (id, o) { return __operitToolsCall("Chat", "getMessages", JSON.stringify([id, o])); },
        updateTitle: function (id, t) { return __operitToolsCall("Chat", "updateTitle", JSON.stringify([id, t])); },
        deleteChat: function (id) { return __operitToolsCall("Chat", "deleteChat", JSON.stringify([id])); }
    },
    System: {
        sendNotification: function (t, b) { return __operitToolsCall("System", "sendNotification", JSON.stringify([t, b])); }
    },
    Workflow: {
        getAll: function (o) { return __operitToolsCall("Workflow", "getAll", JSON.stringify([o])); },
        get: function (id) { return __operitToolsCall("Workflow", "get", JSON.stringify([id])); },
        create: function (w) { return __operitToolsCall("Workflow", "create", JSON.stringify([w])); },
        update: function (id, w) { return __operitToolsCall("Workflow", "update", JSON.stringify([id, w])); },
        delete: function (id) { return __operitToolsCall("Workflow", "delete", JSON.stringify([id])); }
    }
};
"""

        /** CommonJS 加载器 + 模块注册 + 入口调用框架。__operitSources 由运行时填充 */
        private const val LOADER = """
var __operitModules = {};
function __operitNormalize(path) {
    var parts = [], segs = path.split('/');
    for (var i = 0; i < segs.length; i++) {
        var s = segs[i];
        if (s === '' || s === '.') continue;
        if (s === '..') { parts.pop(); continue; }
        parts.push(s);
    }
    return parts.join('/');
}
function __operitResolve(fromDir, request) {
    if (request.charAt(0) === '.') {
        var joined = fromDir ? fromDir + '/' + request : request;
        var norm = __operitNormalize(joined);
        if (__operitSources[norm]) return norm;
        if (__operitSources[norm + '.js']) return norm + '.js';
        return norm;
    }
    if (__operitSources[request]) return request;
    return '';
}
function __operitDir(path) {
    var idx = path.lastIndexOf('/');
    return idx >= 0 ? path.substring(0, idx) : '';
}
function __operitRequire(fromDir, request) {
    var resolved = __operitResolve(fromDir, request);
    if (resolved === '') return {};
    if (__operitModules[resolved]) return __operitModules[resolved].exports;
    var mod = { exports: {} };
    __operitModules[resolved] = mod;
    var dir = __operitDir(resolved);
    __operitSources[resolved](mod.exports, mod, function (r) { return __operitRequire(dir, r); }, dir);
    return mod.exports;
}
function __operitLoadEntry(entry) {
    return __operitRequire('', entry);
}
"""

        private const val INVOKE_FRAME = """
(function () {
    var mod = __operitLoadEntry({ENTRY});
    var tool = mod[{TOOL}];
    if (typeof tool !== 'function') {
        var keys = [];
        for (var k in mod) { if (typeof mod[k] === 'function') keys.push(k); }
        return JSON.stringify({ ok: false, error: 'tool not found: ' + {TOOL}, available: keys });
    }
    var args = {ARGS};
    try {
        var result = tool.call(mod, args);
        return JSON.stringify({ ok: true, data: (result === undefined ? null : result) });
    } catch (e) {
        return JSON.stringify({ ok: false, error: (e && e.message) ? e.message : String(e) });
    }
})()
"""
    }

    data class ToolResult(val ok: Boolean, val message: String, val data: JsonElement?)

    /** 执行插件目录下的 Operit 脚本工具，返回 JSON 结果 */
    fun runTool(pluginDir: File, pluginId: String, toolName: String, argsJson: String): ToolResult {
        val operitDir = File(pluginDir, OPERIT_DIR)
        val files = operitDir.walkTopDown()
            .filter { it.isFile && it.extension == "js" }
            .sortedBy { it.relativeTo(operitDir).path }
            .toList()
        if (files.isEmpty()) return ToolResult(false, "插件缺少 Operit 脚本（$OPERIT_DIR/）", null)
        val entry = resolveEntry(operitDir, files)
        if (entry == null) return ToolResult(false, "无法定位 Operit 脚本入口", null)

        val contextQ = QuickJSContext.create()
        return try {
            contextQ.setMemoryLimit(64 * 1024 * 1024)
            contextQ.setMaxStackSize(16 * 1024 * 1024)
            val dataRoot = File(context.filesDir, "$OPERIT_DATA_ROOT/$pluginId")
            contextQ.globalObject.setProperty(
                "__operitToolsCall",
                JSCallFunction { args ->
                    val ns = args.getOrNull(0) as? String ?: ""
                    val method = args.getOrNull(1) as? String ?: ""
                    val argJson = args.getOrNull(2)?.toString() ?: "[]"
                    handleToolsCall(dataRoot, ns, method, argJson)
                },
            )
            contextQ.evaluate(TOOLPKG_SHIM)
            contextQ.evaluate(TOOLS_SHIM)
            val sources = buildSources(operitDir, files)
            val script = buildString {
                append(OperitJsTranspiler.RUN_GEN_RUNTIME)
                append("\nvar __operitSources = {\n")
                sources.forEach { (rel, code) ->
                    append(jsonEscaped(rel)).append(": function(exports, module, require, __dirname) {\n")
                    append(code)
                    append("\n},\n")
                }
                append("};\n")
                append(LOADER)
                append("\n")
                append(
                    INVOKE_FRAME
                        .replace("{ENTRY}", jsonEscaped(entry))
                        .replace("{TOOL}", jsonEscaped(toolName))
                        .replace("{ARGS}", argsJson.ifBlank { "[]" })
                )
            }
            val raw = contextQ.evaluate(script)?.toString()
            parseResult(raw)
        } catch (e: Throwable) {
            Log.w(TAG, "runTool failed: $pluginId/$toolName", e)
            ToolResult(false, e.message ?: "脚本执行异常", null)
        } finally {
            runCatching { contextQ.destroy() }
        }
    }

    /** 列出插件可用的脚本工具名（从 toolmanifest.json 读取，缺失时返回空） */
    fun listToolNames(pluginDir: File): List<String> {
        val manifest = File(pluginDir, OPERIT_DIR + File.separator + TOOL_MANIFEST)
        if (manifest.exists()) {
            val root = runCatching { json.parseToJsonElement(manifest.readText()).jsonObject }.getOrNull()
            val tools = root?.get("tools")?.jsonArray ?: return emptyList()
            return tools.mapNotNull { element ->
                (element.jsonObject["name"] as? JsonPrimitive)?.content
            }.distinct()
        }
        return emptyList()
    }

    private fun resolveEntry(operitDir: File, files: List<File>): String? {
        val manifest = File(operitDir, "manifest.json")
        if (manifest.exists()) {
            val main = runCatching {
                json.parseToJsonElement(manifest.readText()).jsonObject["main"]?.jsonPrimitive?.content
            }.getOrNull()
            if (!main.isNullOrBlank()) {
                val candidate = main.removePrefix("./")
                if (files.any { it.relativeTo(operitDir).path == candidate }) return candidate
            }
        }
        return files.firstOrNull()?.relativeTo(operitDir)?.path
    }

    private fun buildSources(operitDir: File, files: List<File>): List<Pair<String, String>> {
        return files.mapNotNull { file ->
            val rel = file.relativeTo(operitDir).path.replace('\\', '/')
            val source = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            rel to OperitJsTranspiler.transpile(source)
        }
    }

    private fun parseResult(raw: String?): ToolResult {
        if (raw.isNullOrBlank()) return ToolResult(false, "脚本无返回结果", null)
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return ToolResult(false, "脚本返回格式异常", null)
        val ok = (root["ok"] as? JsonPrimitive)?.content == "true"
        val message = (root["error"] as? JsonPrimitive)?.content
            ?: if (ok) "ok" else "未知错误"
        val data = root["data"]
        return ToolResult(ok, message, data)
    }

    private fun handleToolsCall(dataRoot: File, ns: String, method: String, argJson: String): String {
        val argStrings = runCatching {
            json.parseToJsonElement(argJson).jsonArray.mapNotNull { v ->
                (v as? JsonPrimitive)?.content
            }
        }.getOrNull() ?: emptyList()
        return when (ns) {
            "Files" -> handleFiles(dataRoot, method, argStrings)
            "System" -> handleSystem(method, argStrings)
            else -> Json.encodeToString(
                mapOf("ok" to false, "message" to "RikkaHub 不支持 Operit 的 $ns.$method 运行时 API")
            )
        }
    }

    private fun handleFiles(root: File, method: String, args: List<String>): String {
        fun resolve(p: String): File? {
            val f = File(root, p.trimStart('/'))
            val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: return null
            val canonical = runCatching { f.canonicalPath }.getOrNull() ?: return null
            return if (canonical.startsWith(canonicalRoot)) f else null
        }
        return when (method) {
            "mkdir" -> {
                val f = args.getOrNull(0)?.let { resolve(it) } ?: return Json.encodeToString(false)
                Json.encodeToString(f.exists() || f.mkdirs())
            }
            "exists" -> {
                val f = args.getOrNull(0)?.let { resolve(it) } ?: return Json.encodeToString(false)
                Json.encodeToString(f.exists())
            }
            "read" -> {
                val f = args.getOrNull(0)?.let { resolve(it) } ?: return Json.encodeToString(null as String?)
                if (!f.isFile) Json.encodeToString(null as String?) else Json.encodeToString(f.readText())
            }
            "write" -> {
                val path = args.getOrNull(0) ?: return Json.encodeToString(false)
                val content = args.getOrNull(1) ?: return Json.encodeToString(false)
                val f = resolve(path) ?: return Json.encodeToString(false)
                f.parentFile?.mkdirs()
                Json.encodeToString(runCatching { f.writeText(content); true }.getOrDefault(false))
            }
            "list" -> {
                val f = args.getOrNull(0)?.let { resolve(it) } ?: return Json.encodeToString(emptyList<String>())
                if (!f.isDirectory) Json.encodeToString(emptyList<String>())
                else Json.encodeToString(f.list()?.toList() ?: emptyList())
            }
            "delete" -> {
                val f = args.getOrNull(0)?.let { resolve(it) } ?: return Json.encodeToString(false)
                Json.encodeToString(runCatching { f.delete() }.getOrDefault(false))
            }
            else -> Json.encodeToString(
                mapOf("ok" to false, "message" to "RikkaHub 不支持 Tools.Files.$method")
            )
        }
    }

    private fun handleSystem(method: String, args: List<String>): String {
        if (method == "sendNotification") {
            val title = args.getOrNull(0).orEmpty()
            val body = args.getOrNull(1).orEmpty()
            runCatching { OperitNotifier.show(context, title, body) }
            return Json.encodeToString(true)
        }
        return Json.encodeToString(mapOf("ok" to false, "message" to "RikkaHub 不支持 Tools.System.$method"))
    }

    private fun jsonEscaped(s: String): String = Json.encodeToString(JsonPrimitive(s))
}

/** 用 Android 通知通道发送 Operit 脚本触发的系统通知 */
internal object OperitNotifier {
    private const val CHANNEL_ID = "operit_tools"
    private var prepared = false

    fun show(context: Context, title: String, body: String) {
        val titleSafe = title.ifBlank { "Operit 脚本通知" }
        val bodySafe = body.ifBlank { "" }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "Operit 工具",
            android.app.NotificationManager.IMPORTANCE_DEFAULT,
        )
        if (!prepared) {
            runCatching { notificationManager.createNotificationChannel(channel) }
            prepared = true
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(titleSafe)
            .setContentText(bodySafe)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        runCatching { notificationManager.notify((titleSafe.hashCode() and 0x7fffffff) % 100000, notification) }
    }
}
