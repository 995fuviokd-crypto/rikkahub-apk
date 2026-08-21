package me.rerere.rikkahub.data.operit

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.plugin.PluginManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Operit 脚本执行器：用 QuickJS 在 App 内真实执行 Operit script / ToolPkg 中的
 * JS 工具。脚本是 CommonJS 模块（exports.xxx），依赖全局 Tools.* 运行时，本类：
 *  1. 把 Tools.* 映射为 RikkaHub 本地能力：Files（沙箱文件系统）、Net（HTTP 请求/网页抓取）、
 *     System（sleep/toast/通知/设备信息）、calc（表达式计算）、Chat（本地会话读写，
 *     经 [OperitChatBridge] 桥接宿主会话数据）为真实实现；
 *     UI 自动化、浏览器控制、Java 桥接等依赖 Shizuku/无障碍特权的能力返回结构化受限提示。
 *  2. 提供 CommonJS 加载器、ToolPkg 注册 API 空实现与全局 complete()（Operit 脚本显式完成约定）。
 *  3. 调用导出的工具函数并返回 JSON 结果。
 */
class OperitScriptRuntime(
    private val context: Context,
    private val chatBridge: OperitChatBridge? = null,
) {

    companion object {
        private const val TAG = "OperitScriptRuntime"
        const val OPERIT_DIR = "operit"
        const val TOOL_MANIFEST = "toolmanifest.json"
        private const val OPERIT_DATA_ROOT = "operit-data"
        private const val HTTP_TIMEOUT_MS = 15_000L

        private val json = Json { ignoreUnknownKeys = true }
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

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
function __operitCall(ns, method, args) {
    var raw = __operitToolsCall(ns, method, JSON.stringify(args));
    if (typeof raw === 'string') {
        try { return JSON.parse(raw); } catch (e) { return raw; }
    }
    return raw;
}
function __operitUnavailable(ns, method) {
    return { ok: false, unavailable: true,
             message: 'RikkaHub 不支持 Operit 的 ' + ns + '.' + method + '（需要 UI 自动化/浏览器控制等特权能力）' };
}
globalThis.Tools = {
    Files: {
        mkdir: function (p) { return __operitCall("Files", "mkdir", [p]); },
        exists: function (p) { return __operitCall("Files", "exists", [p]); },
        read: function (p) { return __operitCall("Files", "read", [p]); },
        readText: function (p) { return __operitCall("Files", "read", [p]); },
        readPart: function (p, s, e) { return __operitCall("Files", "readPart", [p, s, e]); },
        write: function (p, c, a) { return __operitCall("Files", "write", [p, c, a]); },
        writeText: function (p, c) { return __operitCall("Files", "write", [p, c, false]); },
        writeBinary: function (p, b) { return __operitCall("Files", "writeBinary", [p, b]); },
        readBinary: function (p) { return __operitCall("Files", "readBinary", [p]); },
        delete: function (p, r) { return __operitCall("Files", "delete", [p, r]); },
        deleteFile: function (p, r) { return __operitCall("Files", "delete", [p, r]); },
        list: function (p) { return __operitCall("Files", "list", [p]); },
        move: function (s, d) { return __operitCall("Files", "move", [s, d]); },
        copy: function (s, d, r) { return __operitCall("Files", "copy", [s, d, r]); },
        find: function (p, pat) { return __operitCall("Files", "find", [p, pat]); },
        grep: function (p, pat) { return __operitCall("Files", "grep", [p, pat]); },
        info: function (p) { return __operitCall("Files", "info", [p]); },
        create: function (p, c) { return __operitCall("Files", "write", [p, c, false]); },
        edit: function (p, old, neu) { return __operitCall("Files", "edit", [p, old, neu]); },
        download: function (u, d) { return __operitCall("Files", "download", [u, d]); },
        open: function (p) { return __operitCall("Files", "open", [p]); },
        share: function (p, t) { return __operitCall("Files", "share", [p, t]); }
    },
    Net: {
        httpGet: function (url, ign) { return __operitCall("Net", "httpGet", [url, ign]); },
        httpPost: function (url, body, ign) { return __operitCall("Net", "httpPost", [url, body, ign]); },
        http: function (opts) { return __operitCall("Net", "http", [opts]); },
        visit: function (url) { return __operitCall("Net", "visit", [url]); },
        uploadFile: function (opts) { return __operitUnavailable("Net", "uploadFile"); }
    },
    System: {
        sleep: function (ms) { return __operitCall("System", "sleep", [ms]); },
        toast: function (msg) { return __operitCall("System", "toast", [msg]); },
        getDeviceInfo: function () { return __operitCall("System", "getDeviceInfo", []); },
        sendNotification: function (message, title) { return __operitCall("System", "sendNotification", [message, title]); },
        startApp: function (o) { return __operitUnavailable("System", "startApp"); },
        stopApp: function (o) { return __operitUnavailable("System", "stopApp"); },
        installApp: function (o) { return __operitUnavailable("System", "installApp"); },
        uninstallApp: function (o) { return __operitUnavailable("System", "uninstallApp"); },
        listApps: function (o) { return __operitUnavailable("System", "listApps"); },
        getNotifications: function (o) { return __operitUnavailable("System", "getNotifications"); },
        getAppUsageTime: function (o) { return __operitUnavailable("System", "getAppUsageTime"); },
        getLocation: function (o) { return __operitUnavailable("System", "getLocation"); },
        openUrl: function (o) { return __operitCall("System", "openUrl", [o]); }
    },
    Chat: {
        listChats: function (o) { return __operitCall("Chat", "listChats", [o]); },
        getMessages: function (id, o) { return __operitCall("Chat", "getMessages", [id, o]); },
        updateTitle: function (id, t) { return __operitCall("Chat", "updateTitle", [id, t]); },
        deleteChat: function (id) { return __operitCall("Chat", "deleteChat", [id]); },
        sendMessage: function (o) { return __operitUnavailable("Chat", "sendMessage"); },
        sendMessageStreaming: function (o) { return __operitUnavailable("Chat", "sendMessageStreaming"); }
    },
    Workflow: {
        getAll: function (o) { return __operitCall("Workflow", "getAll", [o]); },
        get: function (id) { return __operitCall("Workflow", "get", [id]); },
        create: function (w) { return __operitCall("Workflow", "create", [w]); },
        update: function (id, w) { return __operitCall("Workflow", "update", [id, w]); },
        delete: function (id) { return __operitCall("Workflow", "delete", [id]); }
    },
    Memory: {
        query: function (o) { return __operitCall("Memory", "query", [o]); },
        create: function (o) { return __operitCall("Memory", "create", [o]); },
        update: function (o) { return __operitCall("Memory", "update", [o]); },
        deleteMemory: function (o) { return __operitCall("Memory", "deleteMemory", [o]); }
    },
    UI: {
        getPageInfo: function (o) { return __operitUnavailable("UI", "getPageInfo"); },
        pressKey: function (o) { return __operitUnavailable("UI", "pressKey"); },
        swipe: function (o) { return __operitUnavailable("UI", "swipe"); },
        setText: function (o) { return __operitUnavailable("UI", "setText"); },
        click: function (o) { return __operitUnavailable("UI", "click"); }
    },
    FFmpeg: {
        run: function (o) { return __operitUnavailable("FFmpeg", "run"); },
        execute: function (o) { return __operitUnavailable("FFmpeg", "execute"); }
    },
    Tasker: {
        execute: function (o) { return __operitUnavailable("Tasker", "execute"); }
    },
    SoftwareSettings: {
        get: function (o) { return __operitUnavailable("SoftwareSettings", "get"); },
        set: function (o) { return __operitUnavailable("SoftwareSettings", "set"); }
    },
    calc: function (expr) { return __operitCall("calc", "calc", [expr]); }
};
globalThis._ = globalThis.dataUtils = {
    parseJSON: function (s) { try { return JSON.parse(s); } catch (e) { return null; } },
    stringify: function (o) { try { return JSON.stringify(o); } catch (e) { return String(o); } },
    uuid: function () { return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        var r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8); return v.toString(16); }); },
    sleep: function (ms) { return __operitCall("System", "sleep", [ms]); }
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
    var __operitResult;
    globalThis.complete = function (result) { __operitResult = result; };
    try {
        var result = tool.call(mod, args);
        var finalResult = (__operitResult !== undefined) ? __operitResult : result;
        try {
            return JSON.stringify({ ok: true, data: (finalResult === undefined ? null : finalResult) });
        } catch (e) {
            return JSON.stringify({ ok: true, data: String(finalResult) });
        }
    } catch (e) {
        return JSON.stringify({ ok: false, error: (e && e.message) ? e.message : String(e) });
    }
})()
"""
    }

    data class ToolResult(val ok: Boolean, val message: String, val data: JsonElement?)

    /** 插件数据目录（脚本内 Files 工具的沙箱根目录），供 webview 插件页 bridge 复用 */
    fun dataDir(pluginId: String): File = File(context.filesDir, "$OPERIT_DATA_ROOT/$pluginId")

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
                        .replace("{ARGS}", argsJson.ifBlank { "{}" })
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

    /** 列出插件可用的脚本工具名（从 toolmanifest.json 读取，缺失时从脚本 METADATA 注释块解析） */
    fun listToolNames(pluginDir: File): List<String> {
        val manifest = File(pluginDir, OPERIT_DIR + File.separator + TOOL_MANIFEST)
        if (manifest.exists()) {
            val root = runCatching { json.parseToJsonElement(manifest.readText()).jsonObject }.getOrNull()
            val tools = root?.get("tools")?.jsonArray ?: emptyList()
            val names = tools.mapNotNull { element ->
                (element.jsonObject["name"] as? JsonPrimitive)?.content
            }.distinct()
            if (names.isNotEmpty()) return names
        }
        return OperitToolManifest.toolsFromDirectory(File(pluginDir, OPERIT_DIR)).map { it.name }.distinct()
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

    // ---------- Tools 分发 ----------

    private fun handleToolsCall(dataRoot: File, ns: String, method: String, argJson: String): String {
        val args = runCatching {
            json.parseToJsonElement(argJson).jsonArray.toList()
        }.getOrNull() ?: emptyList()
        return try {
            when (ns) {
                "Files" -> handleFiles(dataRoot, method, args)
                "Net" -> handleNet(method, args)
                "System" -> handleSystem(method, args)
                "calc" -> handleCalc(args)
                "Chat" -> handleChat(method, args)
                "Workflow", "Memory" -> Json.encodeToString(
                    mapOf("ok" to false, "unavailable" to true,
                        "message" to "RikkaHub 暂不支持 Operit 的 $ns.$method（Operit 专有数据模型）")
                )
                else -> Json.encodeToString(
                    mapOf("ok" to false, "unavailable" to true,
                        "message" to "RikkaHub 不支持 Operit 的 $ns.$method 运行时 API")
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "toolsCall error: $ns.$method", e)
            Json.encodeToString(
                mapOf("ok" to false, "message" to "执行 $ns.$method 失败：${e.message ?: "未知错误"}")
            )
        }
    }

    // ---------- Files ----------

    private fun handleFiles(root: File, method: String, args: List<JsonElement>): String {
        fun resolve(p: String?): File? {
            if (p.isNullOrBlank()) return null
            val f = File(root, p.trimStart('/'))
            val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: return null
            val canonical = runCatching { f.canonicalPath }.getOrNull() ?: return null
            return if (canonical.startsWith(canonicalRoot)) f else null
        }
        fun str(idx: Int): String? = (args.getOrNull(idx) as? JsonPrimitive)?.content
        fun strOrEmpty(idx: Int): String = str(idx).orEmpty()
        return when (method) {
            "mkdir" -> {
                val f = resolve(str(0)) ?: return Json.encodeToString(okOp("路径无效"))
                val ok = f.exists() || f.mkdirs()
                Json.encodeToString(okOp(if (ok) "已创建" else "创建失败", path = f.path))
            }
            "exists" -> {
                val f = resolve(str(0))
                Json.encodeToString(buildJsonObject { put("exists", f?.exists() == true) })
            }
            "read" -> {
                val f = resolve(str(0))
                if (f == null || !f.isFile) Json.encodeToString(okOp("文件不存在", content = null, path = str(0).orEmpty()))
                else Json.encodeToString(okOp("ok", content = runCatching { f.readText() }.getOrDefault(""), path = f.path))
            }
            "readPart" -> {
                val f = resolve(str(0))
                val start = str(1)?.toIntOrNull() ?: 1
                val end = str(2)?.toIntOrNull()
                if (f == null || !f.isFile) Json.encodeToString(okOp("文件不存在", content = null))
                else {
                    val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
                    val slice = if (end == null) lines.drop(start - 1) else lines.subList(
                        (start - 1).coerceIn(0, lines.size), end.coerceIn(start, lines.size + 1)
                    )
                    Json.encodeToString(okOp("ok", content = slice.joinToString("\n"), path = f.path))
                }
            }
            "write" -> {
                val f = resolve(str(0)) ?: return Json.encodeToString(okOp("路径无效"))
                val content = str(1).orEmpty()
                val append = (args.getOrNull(2) as? JsonPrimitive)?.content == "true"
                f.parentFile?.mkdirs()
                val ok = runCatching { if (append) f.appendText(content) else f.writeText(content); true }.getOrDefault(false)
                Json.encodeToString(okOp(if (ok) "已写入" else "写入失败", path = f.path))
            }
            "writeBinary" -> {
                val f = resolve(str(0)) ?: return Json.encodeToString(okOp("路径无效"))
                val base64 = str(1).orEmpty()
                val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }.getOrNull()
                    ?: return Json.encodeToString(okOp("base64 解析失败"))
                f.parentFile?.mkdirs()
                val ok = runCatching { f.writeBytes(bytes); true }.getOrDefault(false)
                Json.encodeToString(okOp(if (ok) "已写入" else "写入失败", path = f.path))
            }
            "readBinary" -> {
                val f = resolve(str(0))
                if (f == null || !f.isFile) Json.encodeToString(okOp("文件不存在", content = null))
                else {
                    val b64 = runCatching {
                        android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.DEFAULT)
                    }.getOrDefault("")
                    Json.encodeToString(okOp("ok", content = b64, path = f.path))
                }
            }
            "delete" -> {
                val f = resolve(str(0)) ?: return Json.encodeToString(okOp("路径无效"))
                val recursive = (args.getOrNull(1) as? JsonPrimitive)?.content == "true"
                val ok = runCatching {
                    if (recursive && f.isDirectory) f.deleteRecursively() else f.delete()
                }.getOrDefault(false)
                Json.encodeToString(okOp(if (ok) "已删除" else "删除失败", path = f.path))
            }
            "list" -> {
                val f = resolve(str(0))
                val path = f?.path ?: str(0).orEmpty()
                if (f == null || !f.isDirectory) {
                    Json.encodeToString(buildJsonObject {
                        put("ok", true); put("path", path); put("files", JsonArray(emptyList()))
                    })
                } else {
                    val entries = f.listFiles()?.sortedBy { it.name } ?: emptyList()
                    Json.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("path", path)
                        put("files", JsonArray(entries.map { e ->
                            buildJsonObject {
                                put("name", e.name)
                                put("path", e.path)
                                put("type", if (e.isDirectory) "directory" else "file")
                            }
                        }))
                    })
                }
            }
            "move" -> {
                val src = resolve(str(0)); val dst = resolve(str(1))
                if (src == null || dst == null) Json.encodeToString(okOp("路径无效"))
                else {
                    dst.parentFile?.mkdirs()
                    val ok = runCatching { src.renameTo(dst) || (src.copyRecursively(dst) && src.deleteRecursively()) }.getOrDefault(false)
                    Json.encodeToString(okOp(if (ok) "已移动" else "移动失败", path = dst.path))
                }
            }
            "copy" -> {
                val src = resolve(str(0)); val dst = resolve(str(1))
                if (src == null || dst == null) Json.encodeToString(okOp("路径无效"))
                else {
                    dst.parentFile?.mkdirs()
                    val ok = runCatching {
                        if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst, overwrite = true)
                    }.isSuccess
                    Json.encodeToString(okOp(if (ok) "已复制" else "复制失败", path = dst.path))
                }
            }
            "find" -> {
                val f = resolve(str(0)) ?: return Json.encodeToString(okOp("路径无效", files = emptyList()))
                val pattern = str(1).orEmpty()
                val matcher = runCatching { Regex(pattern) }.getOrNull()
                val matches = if (f.isDirectory) {
                    f.walkTopDown().filter { it.isFile }
                        .filter { matcher?.matches(it.name) == true || (matcher == null && it.name.contains(pattern)) }
                        .take(200).map { it.path }.toList()
                } else emptyList()
                Json.encodeToString(okOp("ok", files = matches))
            }
            "grep" -> {
                val f = resolve(str(0)) ?: return Json.encodeToString(okOp("路径无效", files = emptyList()))
                val pattern = str(1).orEmpty()
                val matcher = runCatching { Regex(pattern) }.getOrNull()
                    ?: return Json.encodeToString(okOp("正则无效", files = emptyList()))
                val matches = if (f.isDirectory) {
                    f.walkTopDown().filter { it.isFile && it.extension in setOf("txt", "md", "json", "js", "ts", "xml", "html", "csv", "log") }
                        .mapNotNull { file ->
                            val hit = runCatching { file.readLines().firstOrNull { matcher.containsMatchIn(it) } }.getOrNull()
                            if (hit != null) "${file.path}: $hit" else null
                        }.take(100).toList()
                } else emptyList()
                Json.encodeToString(okOp("ok", files = matches))
            }
            "info" -> {
                val f = resolve(str(0))
                if (f == null || !f.exists()) Json.encodeToString(okOp("文件不存在", content = null))
                else Json.encodeToString(buildJsonObject {
                    put("ok", true)
                    put("name", f.name)
                    put("path", f.path)
                    put("type", if (f.isDirectory) "directory" else "file")
                    put("size", f.length())
                    put("lastModified", f.lastModified())
                })
            }
            "edit" -> {
                val f = resolve(str(0)) ?: return Json.encodeToString(okOp("路径无效"))
                val old = str(1).orEmpty(); val neu = str(2).orEmpty()
                if (!f.isFile) Json.encodeToString(okOp("文件不存在"))
                else {
                    val content = runCatching { f.readText() }.getOrDefault("")
                    if (!content.contains(old)) Json.encodeToString(okOp("未找到待替换内容"))
                    else {
                        val ok = runCatching { f.writeText(content.replace(old, neu)); true }.getOrDefault(false)
                        Json.encodeToString(okOp(if (ok) "已替换" else "替换失败", path = f.path))
                    }
                }
            }
            "download" -> {
                val url = str(0); val dest = str(1)
                if (url.isNullOrBlank()) return Json.encodeToString(okOp("缺少 URL"))
                val f = resolve(dest)
                if (f == null) Json.encodeToString(okOp("路径无效"))
                else {
                    val ok = runCatching {
                        val request = Request.Builder().url(url).build()
                        httpClient.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) return@use false
                            f.parentFile?.mkdirs()
                            f.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
                            true
                        }
                    }.getOrDefault(false)
                    Json.encodeToString(okOp(if (ok) "已下载" else "下载失败", path = f.path))
                }
            }
            "open", "share" -> Json.encodeToString(
                okOp("RikkaHub 无法在脚本中打开/分享本地文件", success = false)
            )
            else -> Json.encodeToString(
                mapOf("ok" to false, "unavailable" to true, "message" to "RikkaHub 不支持 Tools.Files.$method")
            )
        }
    }

    // ---------- Net ----------

    private fun handleNet(method: String, args: List<JsonElement>): String {
        fun str(idx: Int): String? = (args.getOrNull(idx) as? JsonPrimitive)?.content
        return when (method) {
            "httpGet" -> {
                val url = str(0) ?: return Json.encodeToString(opErr("缺少 URL"))
                performHttp("GET", url, null, null)
            }
            "httpPost" -> {
                val url = str(0) ?: return Json.encodeToString(opErr("缺少 URL"))
                val bodyEl = args.getOrNull(1)
                val isJson = bodyEl is JsonObject || bodyEl is JsonArray
                val body = when {
                    bodyEl == null || bodyEl == JsonNull -> null
                    isJson -> bodyEl.toString()
                    else -> (bodyEl as? JsonPrimitive)?.content.orEmpty()
                }
                val contentType = if (isJson) "application/json" else null
                performHttp("POST", url, body, contentType)
            }
            "http" -> {
                val opts = args.getOrNull(0) as? JsonObject ?: return Json.encodeToString(opErr("缺少 options"))
                val url = (opts["url"] as? JsonPrimitive)?.content
                    ?: return Json.encodeToString(opErr("缺少 url"))
                val method = (opts["method"] as? JsonPrimitive)?.content?.uppercase() ?: "GET"
                val body = (opts["body"] as? JsonPrimitive)?.content
                    ?: opts["body"]?.toString()
                val headers = (opts["headers"] as? JsonObject)?.entries
                    ?.filter { it.value is JsonPrimitive }
                    ?.associate { it.key to (it.value as JsonPrimitive).content }
                performHttp(method, url, body, null, headers)
            }
            "visit" -> {
                val url = str(0) ?: return Json.encodeToString(opErr("缺少 URL"))
                val result = runCatching {
                    val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (RikkaHub Operit)").build()
                    httpClient.newCall(request).execute().use { resp ->
                        val html = resp.body?.string().orEmpty()
                        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim()
                        val text = stripHtml(html).trim().take(6000)
                        buildJsonObject {
                            put("ok", true)
                            put("url", url)
                            put("statusCode", resp.code)
                            put("title", title.orEmpty())
                            put("content", text)
                        }
                    }
                }.getOrElse { e -> opErr("访问失败：${e.message ?: "未知错误"}") }
                Json.encodeToString(result)
            }
            else -> Json.encodeToString(
                mapOf("ok" to false, "unavailable" to true, "message" to "RikkaHub 不支持 Tools.Net.$method")
            )
        }
    }

    private fun performHttp(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>? = null,
    ): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Json.encodeToString(opErr("URL 必须为 http/https"))
        }
        val result = runCatching {
            val builder = Request.Builder().url(url)
            if (headers != null) headers.forEach { (k, v) -> builder.header(k, v) }
            if (body == null) {
                builder.method(method.ifBlank { "GET" }, null)
            } else {
                val media = (contentType ?: "text/plain").toMediaType()
                builder.method(method.ifBlank { "POST" }, body.toRequestBody(media))
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                val respHeaders = resp.headers.names().associateWith { resp.headers.values(it).joinToString(", ") }
                buildJsonObject {
                    put("ok", true)
                    put("statusCode", resp.code)
                    put("body", respBody)
                    put("headers", JsonObject(respHeaders.mapValues { JsonPrimitive(it.value) }))
                }
            }
        }.getOrElse { e -> opErr("请求失败：${e.message ?: "未知错误"}") }
        return Json.encodeToString(result)
    }

    private fun stripHtml(html: String): String {
        var text = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        text = text.replace(Regex("[ \\t\\r\\n]{2,}"), "\n").replace(Regex("\\n{3,}"), "\n\n")
        return text
    }

    // ---------- System ----------

    private fun handleSystem(method: String, args: List<JsonElement>): String {
        fun str(idx: Int): String? = (args.getOrNull(idx) as? JsonPrimitive)?.content
        return when (method) {
            "sleep" -> {
                val ms = str(0)?.toLongOrNull()?.coerceIn(0, 300_000L) ?: 0L
                if (ms > 0) runCatching { Thread.sleep(ms) }
                Json.encodeToString(mapOf("ok" to true, "message" to "completed"))
            }
            "toast" -> {
                val msg = str(0).orEmpty()
                runCatching { Toast.makeText(context, msg.ifBlank { " " }, Toast.LENGTH_SHORT).show() }
                Json.encodeToString(mapOf("ok" to true, "message" to "sent"))
            }
            "getDeviceInfo" -> Json.encodeToString(buildJsonObject {
                put("ok", true)
                put("manufacturer", android.os.Build.MANUFACTURER)
                put("model", android.os.Build.MODEL)
                put("brand", android.os.Build.BRAND)
                put("androidVersion", android.os.Build.VERSION.RELEASE)
                put("sdkInt", android.os.Build.VERSION.SDK_INT)
                put("appName", runCatching {
                    context.packageManager.getApplicationInfo(context.packageName, 0).loadLabel(context.packageManager).toString()
                }.getOrDefault("RikkaHub"))
            })
            "sendNotification" -> {
                // Operit 签名：sendNotification(message, title?)
                val message = str(0).orEmpty()
                val title = str(1).orEmpty()
                runCatching { OperitNotifier.show(context, title, message) }
                Json.encodeToString(mapOf("ok" to true, "message" to "sent"))
            }
            "openUrl" -> {
                val url = str(0)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                if (url == null) Json.encodeToString(opErr("URL 无效"))
                else {
                    runCatching {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                    Json.encodeToString(mapOf("ok" to true, "message" to "opened"))
                }
            }
            else -> Json.encodeToString(
                mapOf("ok" to false, "unavailable" to true, "message" to "RikkaHub 不支持 Tools.System.$method")
            )
        }
    }

    // ---------- calc ----------

    private fun handleCalc(args: List<JsonElement>): String {
        val expr = (args.getOrNull(0) as? JsonPrimitive)?.content.orEmpty()
        val value = evalExpression(expr)
        return if (value == null) {
            Json.encodeToString(mapOf("ok" to false, "message" to "表达式无效：$expr"))
        } else {
            Json.encodeToString(buildJsonObject {
                put("ok", true)
                put("expression", expr)
                put("result", value)
            })
        }
    }

    /** 简易四则运算表达式求值，支持 + - * / % 与括号、小数 */
    private fun evalExpression(expr: String): Double? {
        val s = expr.filter { !it.isWhitespace() }
        if (s.isEmpty() || s.length > 256) return null
        if (!s.all { it.isDigit() || it in "+-*/%(). " }) return null
        return OperitCalcExpr(s).eval()
    }

    // ---------- Chat ----------

    /**
     * Operit Chat.* 工具：经 [OperitChatBridge] 映射到 RikkaHub 本地会话。
     * 只读与元数据操作（list/find/getMessages/updateTitle/deleteChat/createNew）真实实现；
     * 依赖宿主界面状态的接口（switchTo/agentStatus/sendMessage/listCharacterCards）返回受限提示。
     */
    private fun handleChat(method: String, args: List<JsonElement>): String {
        val bridge = chatBridge
        fun str(idx: Int): String? = (args.getOrNull(idx) as? JsonPrimitive)?.content
        fun obj(idx: Int): JsonObject? = (args.getOrNull(idx) as? JsonObject)
        fun intArg(o: JsonObject?, key: String): Int? =
            (o?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

        if (bridge == null) {
            return Json.encodeToString(
                mapOf("ok" to false, "unavailable" to true,
                    "message" to "Chat.$method 需要宿主会话数据，当前运行环境未注入 Chat 桥接")
            )
        }
        return try {
            when (method) {
                "listAll", "listChats" -> {
                    val params = if (method == "listChats") obj(0) else null
                    val chats = bridge.listChats(
                        query = (params?.get("query") as? JsonPrimitive)?.contentOrNull,
                        match = (params?.get("match") as? JsonPrimitive)?.contentOrNull,
                        limit = intArg(params, "limit"),
                        sortBy = (params?.get("sort_by") as? JsonPrimitive)?.contentOrNull,
                        sortOrder = (params?.get("sort_order") as? JsonPrimitive)?.contentOrNull,
                    )
                    Json.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("success", true)
                        put("message", "找到 ${chats.size} 个会话")
                        put("chats", JsonArray(chats.map { Json.encodeToJsonElement(it) }))
                    })
                }
                "findChat" -> {
                    val params = obj(0) ?: return Json.encodeToString(opErr("findChat 需要 query 参数"))
                    val query = (params["query"] as? JsonPrimitive)?.contentOrNull
                        ?: return Json.encodeToString(opErr("findChat 缺少 query"))
                    val chat = bridge.findChat(
                        query = query,
                        match = (params["match"] as? JsonPrimitive)?.contentOrNull,
                        index = intArg(params, "index") ?: 0,
                    )
                    if (chat == null) {
                        Json.encodeToString(mapOf("ok" to false, "success" to false, "message" to "未找到匹配会话"))
                    } else {
                        Json.encodeToString(buildJsonObject {
                            put("ok", true)
                            put("success", true)
                            put("chat", Json.encodeToJsonElement(chat))
                        })
                    }
                }
                "getMessages" -> {
                    val chatId = str(0) ?: return Json.encodeToString(opErr("getMessages 需要 chatId"))
                    val params = obj(1)
                    val messages = bridge.getMessages(
                        chatId = chatId,
                        order = (params?.get("order") as? JsonPrimitive)?.contentOrNull,
                        limit = intArg(params, "limit"),
                    )
                    Json.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("success", true)
                        put("chatId", chatId)
                        put("messages", JsonArray(messages.map { Json.encodeToJsonElement(it) }))
                    })
                }
                "getMessagesRange" -> {
                    val chatId = str(0) ?: return Json.encodeToString(opErr("getMessagesRange 需要 chatId"))
                    val params = obj(1) ?: return Json.encodeToString(opErr("getMessagesRange 需要 {start, end}"))
                    val start = intArg(params, "start") ?: return Json.encodeToString(opErr("getMessagesRange 缺少 start"))
                    val end = intArg(params, "end") ?: return Json.encodeToString(opErr("getMessagesRange 缺少 end"))
                    val messages = bridge.getMessagesRange(
                        chatId = chatId,
                        order = (params["order"] as? JsonPrimitive)?.contentOrNull,
                        start = start,
                        end = end,
                    )
                    Json.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("success", true)
                        put("chatId", chatId)
                        put("messages", JsonArray(messages.map { Json.encodeToJsonElement(it) }))
                    })
                }
                "updateTitle" -> {
                    val chatId = str(0) ?: return Json.encodeToString(opErr("updateTitle 需要 chatId"))
                    val title = str(1) ?: return Json.encodeToString(opErr("updateTitle 需要 title"))
                    val ok = bridge.updateTitle(chatId, title)
                    Json.encodeToString(if (ok) {
                        mapOf("ok" to true, "success" to true, "message" to "已更新标题")
                    } else {
                        mapOf("ok" to false, "success" to false, "message" to "会话不存在")
                    })
                }
                "deleteChat" -> {
                    val chatId = str(0) ?: return Json.encodeToString(opErr("deleteChat 需要 chatId"))
                    val ok = bridge.deleteChat(chatId)
                    Json.encodeToString(if (ok) {
                        mapOf("ok" to true, "success" to true, "message" to "已删除会话 $chatId")
                    } else {
                        mapOf("ok" to false, "success" to false, "message" to "会话不存在")
                    })
                }
                "createNew" -> {
                    val group = str(0)
                    val chat = bridge.createNew(group)
                    if (chat == null) {
                        Json.encodeToString(opErr("创建会话失败"))
                    } else {
                        Json.encodeToString(buildJsonObject {
                            put("ok", true)
                            put("success", true)
                            put("message", "已创建会话 ${chat.title}")
                            put("chat", Json.encodeToJsonElement(chat))
                        })
                    }
                }
                "switchTo", "agentStatus" -> Json.encodeToString(
                    mapOf("ok" to false, "unavailable" to true,
                        "message" to "Chat.$method 需要切换 App 界面会话状态，RikkaHub 不支持该操作")
                )
                "sendMessage", "sendMessageStreaming" -> Json.encodeToString(
                    mapOf("ok" to false, "unavailable" to true,
                        "message" to "Chat.$method 依赖 App 内 AI 会话发送流程，RikkaHub 暂不开放该能力")
                )
                "listCharacterCards" -> Json.encodeToString(
                    mapOf("ok" to true, "success" to true, "cards" to JsonArray(emptyList()),
                        "message" to "RikkaHub 无角色卡概念，返回空列表")
                )
                else -> Json.encodeToString(
                    mapOf("ok" to false, "unavailable" to true,
                        "message" to "RikkaHub 暂不支持 Chat.$method")
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "chat call error: $method", e)
            Json.encodeToString(
                mapOf("ok" to false, "message" to "执行 Chat.$method 失败：${e.message ?: "未知错误"}")
            )
        }
    }

    // ---------- 结果构造 ----------

    private fun okOp(message: String, content: String? = null, path: String? = null, success: Boolean = true, files: List<String>? = null): JsonObject {
        return buildJsonObject {
            put("ok", success)
            put("success", success)
            put("message", message)
            if (content != null) put("content", content)
            if (path != null) put("path", path)
            if (files != null) put("files", JsonArray(files.map { JsonPrimitive(it) }))
        }
    }

    private fun opErr(message: String): JsonObject {
        return buildJsonObject {
            put("ok", false)
            put("success", false)
            put("message", message)
        }
    }

    private fun jsonEscaped(s: String): String = Json.encodeToString(JsonPrimitive(s))
}

/** 递归下降的简易表达式求值器（+ - * / % 与括号、小数） */
private class OperitCalcExpr(private val s: String) {
    private var pos = 0

    fun eval(): Double? {
        val r = expr() ?: return null
        return if (pos == s.length) r else null
    }

    private fun peek(): Char? = s.getOrNull(pos)

    private fun expr(): Double? {
        var v = term() ?: return null
        while (true) {
            when (peek()) {
                '+' -> { pos++; val r = term() ?: return null; v += r }
                '-' -> { pos++; val r = term() ?: return null; v -= r }
                else -> return v
            }
        }
    }

    private fun term(): Double? {
        var v = factor() ?: return null
        while (true) {
            when (peek()) {
                '*' -> { pos++; val r = factor() ?: return null; v *= r }
                '/' -> { pos++; val r = factor() ?: return null; if (r == 0.0) return null; v /= r }
                '%' -> { pos++; val r = factor() ?: return null; v %= r }
                else -> return v
            }
        }
    }

    private fun factor(): Double? {
        if (peek() == '(') {
            pos++
            val v = expr() ?: return null
            if (peek() != ')') return null
            pos++
            return v
        }
        if (peek() == '-') {
            pos++
            return -(factor() ?: return null)
        }
        val start = pos
        while (peek()?.isDigit() == true || peek() == '.') pos++
        if (start == pos) return null
        return s.substring(start, pos).toDoubleOrNull()
    }
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
