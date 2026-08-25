package me.rerere.rikkahub.data.script

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.runBlocking
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
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Workflow
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkflowRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 脚本执行器：用 QuickJS 在 App 内真实执行 script / ToolPkg 中的
 * JS 工具。脚本是 CommonJS 模块（exports.xxx），依赖全局 Tools.* 运行时，本类：
 *  1. 把 Tools.* 映射为 RikkaHub 本地能力：Files（沙箱文件系统）、Net（HTTP 请求/网页抓取）、
 *     System（sleep/toast/通知/设备信息）、calc（表达式计算）、Chat（本地会话读写，
 *     经 [ScriptChatBridge] 桥接宿主会话数据）为真实实现；
 *     UI 自动化、浏览器控制、Java 桥接等依赖 Shizuku/无障碍特权的能力返回结构化受限提示。
 *  2. 提供 CommonJS 加载器、ToolPkg 注册 API 空实现与全局 complete()（脚本显式完成约定）。
 *  3. 调用导出的工具函数并返回 JSON 结果。
 */
open class ScriptRuntime(
    private val context: Context,
    private val chatBridge: ScriptChatBridge? = null,
    private val workflowRepository: WorkflowRepository? = null,
    private val memoryRepository: MemoryRepository? = null,
) {

    companion object {
        private const val TAG = "ScriptRuntime"
        const val SCRIPT_DIR = "script"
        const val LEGACY_SCRIPT_DIR = "operit"
        const val TOOL_MANIFEST = "toolmanifest.json"
        private const val SCRIPT_DATA_ROOT = "script-data"
        private const val LEGACY_SCRIPT_DATA_ROOT = "operit-data"
        private const val HTTP_TIMEOUT_MS = 15_000L

        /** 解析插件脚本目录：优先新版 script/，兼容旧版 operit/ 磁盘布局 */
        fun scriptDir(pluginDir: File): File {
            val dir = File(pluginDir, SCRIPT_DIR)
            if (dir.isDirectory) return dir
            val legacy = File(pluginDir, LEGACY_SCRIPT_DIR)
            return if (legacy.isDirectory) legacy else dir
        }

        private val json = Json { ignoreUnknownKeys = true }
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        /** ToolPkg 运行时注册 API 的适配实现，防止 main.js 执行时崩溃；数据目录落在插件沙箱 data */
        private const val TOOLPKG_SHIM = """
globalThis.ToolPkg = globalThis.ToolPkg || {
    registerUiRoute: function () { return true; },
    registerNavigationEntry: function () { return true; },
    registerSettingsEntry: function () { return true; },
    getConfigDir: function () { return 'data'; },
    readResource: function () { return null; },
    ipc: { call: function () { return null; }, on: function () { return true; } },
    _m: function () { return true; }
};
"""

        /** Tools.* 全局对象：方法统一转发到 Java 侧 __scriptToolsCall(namespace, method, argsJson) */
        private const val TOOLS_SHIM = """
function __scriptCall(ns, method, args) {
    var raw = __scriptToolsCall(ns, method, JSON.stringify(args));
    if (typeof raw === 'string') {
        try { return JSON.parse(raw); } catch (e) { return raw; }
    }
    return raw;
}
function __scriptUnavailable(ns, method) {
    return { ok: false, unavailable: true,
             message: 'RikkaHub 不支持 ' + ns + '.' + method + '（需要 UI 自动化/浏览器控制等特权能力）' };
}
globalThis.Tools = {
    Files: {
        mkdir: function (p) { return __scriptCall("Files", "mkdir", [p]); },
        exists: function (p) { return __scriptCall("Files", "exists", [p]); },
        read: function (p) { return __scriptCall("Files", "read", [p]); },
        readText: function (p) { return __scriptCall("Files", "read", [p]); },
        readPart: function (p, s, e) { return __scriptCall("Files", "readPart", [p, s, e]); },
        write: function (p, c, a) { return __scriptCall("Files", "write", [p, c, a]); },
        writeText: function (p, c) { return __scriptCall("Files", "write", [p, c, false]); },
        writeBinary: function (p, b) { return __scriptCall("Files", "writeBinary", [p, b]); },
        readBinary: function (p) { return __scriptCall("Files", "readBinary", [p]); },
        delete: function (p, r) { return __scriptCall("Files", "delete", [p, r]); },
        deleteFile: function (p, r) { return __scriptCall("Files", "delete", [p, r]); },
        list: function (p) { return __scriptCall("Files", "list", [p]); },
        move: function (s, d) { return __scriptCall("Files", "move", [s, d]); },
        copy: function (s, d, r) { return __scriptCall("Files", "copy", [s, d, r]); },
        find: function (p, pat) { return __scriptCall("Files", "find", [p, pat]); },
        grep: function (p, pat) { return __scriptCall("Files", "grep", [p, pat]); },
        info: function (p) { return __scriptCall("Files", "info", [p]); },
        create: function (p, c) { return __scriptCall("Files", "write", [p, c, false]); },
        edit: function (p, old, neu) { return __scriptCall("Files", "edit", [p, old, neu]); },
        download: function (u, d) { return __scriptCall("Files", "download", [u, d]); },
        open: function (p) { return __scriptCall("Files", "open", [p]); },
        share: function (p, t) { return __scriptCall("Files", "share", [p, t]); }
    },
    Net: {
        httpGet: function (url, ign) { return __scriptCall("Net", "httpGet", [url, ign]); },
        httpPost: function (url, body, ign) { return __scriptCall("Net", "httpPost", [url, body, ign]); },
        http: function (opts) { return __scriptCall("Net", "http", [opts]); },
        visit: function (url) { return __scriptCall("Net", "visit", [url]); },
        uploadFile: function (opts) { return __scriptCall("Net", "uploadFile", [opts]); }
    },
    System: {
        sleep: function (ms) { return __scriptCall("System", "sleep", [ms]); },
        toast: function (msg) { return __scriptCall("System", "toast", [msg]); },
        getDeviceInfo: function () { return __scriptCall("System", "getDeviceInfo", []); },
        sendNotification: function (message, title) { return __scriptCall("System", "sendNotification", [message, title]); },
        startApp: function (o) { return __scriptCall("System", "startApp", [o]); },
        stopApp: function (o) { return __scriptUnavailable("System", "stopApp"); },
        installApp: function (o) { return __scriptUnavailable("System", "installApp"); },
        uninstallApp: function (o) { return __scriptUnavailable("System", "uninstallApp"); },
        listApps: function (o) { return __scriptCall("System", "listApps", [o]); },
        getNotifications: function (o) { return __scriptUnavailable("System", "getNotifications"); },
        getAppUsageTime: function (o) { return __scriptUnavailable("System", "getAppUsageTime"); },
        getLocation: function (o) { return __scriptUnavailable("System", "getLocation"); },
        openUrl: function (o) { return __scriptCall("System", "openUrl", [o]); }
    },
    Chat: {
        listChats: function (o) { return __scriptCall("Chat", "listChats", [o]); },
        getMessages: function (id, o) { return __scriptCall("Chat", "getMessages", [id, o]); },
        updateTitle: function (id, t) { return __scriptCall("Chat", "updateTitle", [id, t]); },
        deleteChat: function (id) { return __scriptCall("Chat", "deleteChat", [id]); },
        sendMessage: function (o) { return __scriptCall("Chat", "sendMessage", [o]); },
        sendMessageStreaming: function (o) { return __scriptCall("Chat", "sendMessageStreaming", [o]); }
    },
    Workflow: {
        getAll: function (o) { return __scriptCall("Workflow", "getAll", [o]); },
        get: function (id) { return __scriptCall("Workflow", "get", [id]); },
        create: function (w) { return __scriptCall("Workflow", "create", [w]); },
        update: function (id, w) { return __scriptCall("Workflow", "update", [id, w]); },
        delete: function (id) { return __scriptCall("Workflow", "delete", [id]); }
    },
    Memory: {
        query: function (o) { return __scriptCall("Memory", "query", [o]); },
        create: function (o) { return __scriptCall("Memory", "create", [o]); },
        update: function (o) { return __scriptCall("Memory", "update", [o]); },
        deleteMemory: function (o) { return __scriptCall("Memory", "deleteMemory", [o]); }
    },
    UI: {
        getPageInfo: function (o) { return __scriptUnavailable("UI", "getPageInfo"); },
        pressKey: function (o) { return __scriptUnavailable("UI", "pressKey"); },
        swipe: function (o) { return __scriptUnavailable("UI", "swipe"); },
        setText: function (o) { return __scriptUnavailable("UI", "setText"); },
        click: function (o) { return __scriptUnavailable("UI", "click"); }
    },
    FFmpeg: {
        run: function (o) { return __scriptUnavailable("FFmpeg", "run"); },
        execute: function (o) { return __scriptUnavailable("FFmpeg", "execute"); }
    },
    Tasker: {
        execute: function (o) { return __scriptUnavailable("Tasker", "execute"); }
    },
    SoftwareSettings: {
        get: function (o) { return __scriptUnavailable("SoftwareSettings", "get"); },
        set: function (o) { return __scriptUnavailable("SoftwareSettings", "set"); }
    },
    calc: function (expr) { return __scriptCall("calc", "calc", [expr]); }
};
globalThis._ = globalThis.dataUtils = {
    parseJSON: function (s) { try { return JSON.parse(s); } catch (e) { return null; } },
    stringify: function (o) { try { return JSON.stringify(o); } catch (e) { return String(o); } },
    uuid: function () { return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        var r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8); return v.toString(16); }); },
    sleep: function (ms) { return __scriptCall("System", "sleep", [ms]); }
};
"""

        /** CommonJS 加载器 + 模块注册 + 入口调用框架。__scriptSources 由运行时填充 */
        private const val LOADER = """
var __scriptModules = {};
function __scriptNormalize(path) {
    var parts = [], segs = path.split('/');
    for (var i = 0; i < segs.length; i++) {
        var s = segs[i];
        if (s === '' || s === '.') continue;
        if (s === '..') { parts.pop(); continue; }
        parts.push(s);
    }
    return parts.join('/');
}
function __scriptResolve(fromDir, request) {
    if (request.charAt(0) === '.') {
        var joined = fromDir ? fromDir + '/' + request : request;
        var norm = __scriptNormalize(joined);
        if (__scriptSources[norm]) return norm;
        if (__scriptSources[norm + '.js']) return norm + '.js';
        return norm;
    }
    if (__scriptSources[request]) return request;
    return '';
}
function __scriptDir(path) {
    var idx = path.lastIndexOf('/');
    return idx >= 0 ? path.substring(0, idx) : '';
}
function __scriptRequire(fromDir, request) {
    var resolved = __scriptResolve(fromDir, request);
    if (resolved === '') return {};
    if (__scriptModules[resolved]) return __scriptModules[resolved].exports;
    var mod = { exports: {} };
    __scriptModules[resolved] = mod;
    var dir = __scriptDir(resolved);
    __scriptSources[resolved](mod.exports, mod, function (r) { return __scriptRequire(dir, r); }, dir);
    return mod.exports;
}
function __scriptLoadEntry(entry) {
    return __scriptRequire('', entry);
}
"""

        private const val INVOKE_FRAME = """
(function () {
    var mod = __scriptLoadEntry({ENTRY});
    var tool = mod[{TOOL}];
    if (typeof tool !== 'function') {
        var keys = [];
        for (var k in mod) { if (typeof mod[k] === 'function') keys.push(k); }
        return JSON.stringify({ ok: false, error: 'tool not found: ' + {TOOL}, available: keys });
    }
    var args = {ARGS};
    var __scriptResult;
    globalThis.complete = function (result) { __scriptResult = result; };
    try {
        var result = tool.call(mod, args);
        var finalResult = (__scriptResult !== undefined) ? __scriptResult : result;
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

        // 动态 Hook 调用帧：调用入口导出的 rikkaHook(ctx)，返回修改后的上下文。
        // 未导出 / 抛异常时返回 ok:false，由宿主决定跳过该插件继续 Hook 链
        private const val HOOK_INVOKE_FRAME = """
(function () {
    var mod = __scriptLoadEntry({ENTRY});
    var fn = null;
    if (mod && typeof mod.rikkaHook === 'function') fn = mod.rikkaHook;
    else if (typeof globalThis.rikkaHook === 'function') fn = globalThis.rikkaHook;
    if (!fn) {
        return JSON.stringify({ ok: false, error: 'rikkaHook not exported (use module.exports = { rikkaHook })' });
    }
    try {
        var ctx = {PAYLOAD};
        var result = fn.call(mod, ctx);
        if (result === undefined || result === null) result = ctx;
        try {
            return JSON.stringify({ ok: true, data: result });
        } catch (e) {
            return JSON.stringify({ ok: true, data: String(result) });
        }
    } catch (e) {
        return JSON.stringify({ ok: false, error: (e && e.message) ? e.message : String(e) });
    }
})()
"""
    }

    data class ToolResult(val ok: Boolean, val message: String, val data: JsonElement?)

    /** 插件数据目录（脚本内 Files 工具的沙箱根目录），供 webview 插件页 bridge 复用 */
    fun dataDir(pluginId: String): File = dataRoot(pluginId)

    private fun dataRoot(pluginId: String): File {
        val dir = File(context.filesDir, "$SCRIPT_DATA_ROOT/$pluginId")
        val legacy = File(context.filesDir, "$LEGACY_SCRIPT_DATA_ROOT/$pluginId")
        return if (dir.isDirectory) dir else if (legacy.isDirectory) legacy else dir
    }

    /** 执行插件目录下的 脚本工具，返回 JSON 结果 */
    fun runTool(pluginDir: File, pluginId: String, toolName: String, argsJson: String): ToolResult {
        val scriptDir = scriptDir(pluginDir)
        val files = listScriptFiles(scriptDir)
        if (files.isEmpty()) return ToolResult(false, "插件缺少 脚本（$SCRIPT_DIR/）", null)
        val entry = resolveEntry(scriptDir, files)
        if (entry == null) return ToolResult(false, "无法定位 脚本入口", null)

        val frame = INVOKE_FRAME
            .replace("{ENTRY}", jsonEscaped(entry))
            .replace("{TOOL}", jsonEscaped(toolName))
            .replace("{ARGS}", argsJson.ifBlank { "{}" })
        return evaluateEntry(pluginDir, pluginId, scriptDir, files, entry, frame, logTag = "runTool")
    }

    /**
     * 执行插件动态 Hook：以 payload JSON 为上下文调用入口导出的 rikkaHook(ctx) 函数。
     * 插件侧约定（CommonJS）：module.exports = { rikkaHook }，或定义全局 rikkaHook；
     * 返回值作为修改后的上下文（undefined/null 时保持原 ctx 不变）。
     * 与 [runTool] 共享同一套 shim / 沙箱 / 加载链，插件脚本内可用全部 Tools API。
     */
    open fun runHook(pluginDir: File, pluginId: String, hookName: String, payloadJson: String): ToolResult {
        val scriptDir = scriptDir(pluginDir)
        val files = listScriptFiles(scriptDir)
        if (files.isEmpty()) return ToolResult(false, "插件缺少 脚本（$SCRIPT_DIR/）", null)
        val entry = resolveEntry(scriptDir, files)
        if (entry == null) return ToolResult(false, "无法定位 脚本入口", null)

        val frame = HOOK_INVOKE_FRAME
            .replace("{ENTRY}", jsonEscaped(entry))
            .replace("{PAYLOAD}", payloadJson.ifBlank { "{}" })
        return evaluateEntry(
            pluginDir, pluginId, scriptDir, files, entry, frame,
            logTag = "runHook:$hookName",
        )
    }

    private fun listScriptFiles(scriptDir: File): List<File> {
        return scriptDir.walkTopDown()
            .filter { it.isFile && it.extension == "js" }
            .sortedBy { it.relativeTo(scriptDir).path }
            .toList()
    }

    private fun evaluateEntry(
        pluginDir: File,
        pluginId: String,
        scriptDir: File,
        files: List<File>,
        entry: String,
        invokeFrame: String,
        logTag: String,
    ): ToolResult {
        val contextQ = QuickJSContext.create()
        return try {
            contextQ.setMemoryLimit(64 * 1024 * 1024)
            contextQ.setMaxStackSize(16 * 1024 * 1024)
            val dataRoot = dataRoot(pluginId)
            contextQ.globalObject.setProperty(
                "__scriptToolsCall",
                JSCallFunction { args ->
                    val ns = args.getOrNull(0) as? String ?: ""
                    val method = args.getOrNull(1) as? String ?: ""
                    val argJson = args.getOrNull(2)?.toString() ?: "[]"
                    handleToolsCall(dataRoot, ns, method, argJson)
                },
            )
            contextQ.evaluate(TOOLPKG_SHIM)
            contextQ.evaluate(TOOLS_SHIM)
            val sources = buildSources(scriptDir, pluginDir, files)
            val script = buildString {
                append(ScriptJsTranspiler.RUN_GEN_RUNTIME)
                append("\nvar __scriptSources = {\n")
                sources.forEach { (rel, code) ->
                    append(jsonEscaped(rel)).append(": function(exports, module, require, __dirname) {\n")
                    append(code)
                    append("\n},\n")
                }
                append("};\n")
                append(LOADER)
                append("\n")
                append(invokeFrame)
            }
            val raw = contextQ.evaluate(script)?.toString()
            parseResult(raw)
        } catch (e: Throwable) {
            Log.w(TAG, "$logTag failed: $pluginId", e)
            ToolResult(false, e.message ?: "脚本执行异常", null)
        } finally {
            runCatching { contextQ.destroy() }
        }
    }

    /** 列出插件可用的脚本工具名（从 toolmanifest.json 读取，缺失时从脚本 METADATA 注释块解析） */
    fun listToolNames(pluginDir: File): List<String> {
        val scriptDir = scriptDir(pluginDir)
        val manifest = File(scriptDir, TOOL_MANIFEST)
        if (manifest.exists()) {
            val root = runCatching { json.parseToJsonElement(manifest.readText()).jsonObject }.getOrNull()
            val tools = root?.get("tools")?.jsonArray ?: emptyList()
            val names = tools.mapNotNull { element ->
                (element.jsonObject["name"] as? JsonPrimitive)?.content
            }.distinct()
            if (names.isNotEmpty()) return names
        }
        return ScriptToolManifest.toolsFromDirectory(scriptDir).map { it.name }.distinct()
    }

    private fun resolveEntry(scriptDir: File, files: List<File>): String? {
        val manifest = File(scriptDir, "manifest.json")
        if (manifest.exists()) {
            val main = runCatching {
                json.parseToJsonElement(manifest.readText()).jsonObject["main"]?.jsonPrimitive?.content
            }.getOrNull()
            if (!main.isNullOrBlank()) {
                val candidate = main.removePrefix("./")
                if (files.any { it.relativeTo(scriptDir).path == candidate }) return candidate
            }
        }
        return files.firstOrNull()?.relativeTo(scriptDir)?.path
    }

    private fun buildSources(
        scriptDir: File,
        pluginDir: File,
        files: List<File>,
    ): List<Pair<String, String>> {
        val result = linkedMapOf<String, String>()
        files.forEach { file ->
            val rel = file.relativeTo(scriptDir).path.replace('\\', '/')
            if (result.containsKey(rel)) return@forEach
            val source = runCatching { file.readText() }.getOrNull() ?: return@forEach
            result[rel] = ScriptJsTranspiler.transpile(source)
        }
        // 包根 shared/ 共享目录：逻辑 key 为 "shared/<rel>"，兼容 ../shared/xxx require（loader 会 normalize）
        val sharedDir = File(pluginDir, "shared")
        if (sharedDir.isDirectory) {
            sharedDir.walkTopDown()
                .filter { it.isFile && it.extension == "js" }
                .sortedBy { it.relativeTo(sharedDir).path }
                .forEach { file ->
                    val rel = "shared/" + file.relativeTo(sharedDir).path.replace('\\', '/')
                    if (result.containsKey(rel)) return@forEach
                    val source = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    result[rel] = ScriptJsTranspiler.transpile(source)
                }
        }
        return result.toList()
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
                "Files" -> Json.encodeToString(ScriptFilesSandbox(dataRoot).handle(method, args))
                "Net" -> handleNet(dataRoot, method, args)
                "System" -> handleSystem(method, args)
                "calc" -> handleCalc(args)
                "Chat" -> handleChat(method, args)
                "Workflow" -> handleWorkflow(method, args)
                "Memory" -> handleMemory(method, args)
                else -> Json.encodeToString(
                    mapOf("ok" to false, "unavailable" to true,
                        "message" to "RikkaHub 不支持 $ns.$method 运行时 API")
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "toolsCall error: $ns.$method", e)
            Json.encodeToString(
                mapOf("ok" to false, "message" to "执行 $ns.$method 失败：${e.message ?: "未知错误"}")
            )
        }
    }

    // ---------- Net ----------

    private fun handleNet(dataRoot: File, method: String, args: List<JsonElement>): String {
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
                    val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (RikkaHub)").build()
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
            "uploadFile" -> {
                val opts = args.getOrNull(0) as? JsonObject
                    ?: return Json.encodeToString(opErr("uploadFile 缺少 options"))
                val url = (opts["url"] as? JsonPrimitive)?.content
                    ?: return Json.encodeToString(opErr("uploadFile 缺少 url"))
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return Json.encodeToString(opErr("URL 必须为 http/https"))
                }
                val filePath = (opts["filePath"] as? JsonPrimitive)?.content
                    ?: (opts["path"] as? JsonPrimitive)?.content
                    ?: return Json.encodeToString(opErr("uploadFile 缺少 filePath（插件数据目录内相对路径）"))
                // 经沙箱读取文件，路径解析与防穿越复用 Files 沙箱规则
                val readResult = ScriptFilesSandbox(dataRoot).handle("readBinary", listOf(JsonPrimitive(filePath)))
                val base64 = readResult["contentBase64"]?.jsonPrimitive?.contentOrNull
                if (base64.isNullOrBlank()) {
                    return Json.encodeToString(opErr("沙箱内文件不存在：$filePath"))
                }
                val fileBytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
                    ?: return Json.encodeToString(opErr("文件读取失败：$filePath"))
                val fieldName = (opts["fieldName"] as? JsonPrimitive)?.content ?: "file"
                val filename = (opts["filename"] as? JsonPrimitive)?.content ?: filePath.substringAfterLast('/')
                val contentType = (opts["contentType"] as? JsonPrimitive)?.content ?: "application/octet-stream"
                val fields = (opts["fields"] as? JsonObject)?.entries
                    ?.filter { it.value is JsonPrimitive }
                    ?.associate { it.key to (it.value as JsonPrimitive).content }
                val headers = (opts["headers"] as? JsonObject)?.entries
                    ?.filter { it.value is JsonPrimitive }
                    ?.associate { it.key to (it.value as JsonPrimitive).content }
                val result = runCatching {
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                        fields?.forEach { (k, v) -> addFormDataPart(k, v) }
                        addFormDataPart(fieldName, filename, fileBytes.toRequestBody(contentType.toMediaType()))
                    }.build()
                    val builder = Request.Builder().url(url).post(body)
                    headers?.forEach { (k, v) -> builder.header(k, v) }
                    httpClient.newCall(builder.build()).execute().use { resp ->
                        buildJsonObject {
                            put("ok", true)
                            put("statusCode", resp.code)
                            put("body", resp.body?.string().orEmpty())
                        }
                    }
                }.getOrElse { e -> opErr("上传失败：${e.message ?: "未知错误"}") }
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
                // 脚本签名：sendNotification(message, title?)
                val message = str(0).orEmpty()
                val title = str(1).orEmpty()
                runCatching { ScriptNotifier.show(context, title, message) }
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
            "startApp" -> {
                val raw = args.getOrNull(0)
                val pkg = when (raw) {
                    is JsonPrimitive -> raw.content
                    is JsonObject -> (raw["package"] as? JsonPrimitive)?.content
                        ?: (raw["pkg"] as? JsonPrimitive)?.content
                        ?: (raw["packageName"] as? JsonPrimitive)?.content
                    else -> null
                }
                if (pkg.isNullOrBlank()) {
                    Json.encodeToString(opErr("startApp 需要 package"))
                } else {
                    val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (launch == null) {
                        Json.encodeToString(mapOf("ok" to false, "message" to "未找到可启动的应用：$pkg"))
                    } else {
                        runCatching {
                            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launch)
                        }.onFailure { e ->
                            return Json.encodeToString(opErr("启动失败：${e.message ?: "未知错误"}"))
                        }
                        Json.encodeToString(mapOf("ok" to true, "message" to "已启动 $pkg"))
                    }
                }
            }
            "listApps" -> {
                val opts = args.getOrNull(0) as? JsonObject
                val query = (opts?.get("query") as? JsonPrimitive)?.content?.trim()
                val limit = (opts?.get("limit") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 200
                val apps = runCatching {
                    context.packageManager.getInstalledApplications(0)
                        .asSequence()
                        .filter { it.enabled }
                        .map { ai ->
                            val label = runCatching {
                                ai.loadLabel(context.packageManager).toString()
                            }.getOrDefault(ai.packageName)
                            ai.packageName to label
                        }
                        .filter { (pkg, label) ->
                            query.isNullOrBlank() ||
                                pkg.contains(query, ignoreCase = true) ||
                                label.contains(query, ignoreCase = true)
                        }
                        .sortedBy { it.second.lowercase() }
                        .take(limit.coerceIn(1, 500))
                        .toList()
                }.getOrDefault(emptyList())
                Json.encodeToString(buildJsonObject {
                    put("ok", true)
                    put("success", true)
                    put("message", "共 ${apps.size} 个应用")
                    put("apps", JsonArray(apps.map { (pkg, label) ->
                        buildJsonObject {
                            put("package", pkg)
                            put("name", label)
                        }
                    }))
                })
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
        return ScriptCalcExpr(s).eval()
    }

    // ---------- Workflow ----------

    /** Workflow.* 工具：桥接 RikkaHub 本地工作流数据（getAll/get/create/update/delete）。 */
    private fun handleWorkflow(method: String, args: List<JsonElement>): String {
        val repo = workflowRepository
        if (repo == null) {
            return Json.encodeToString(
                mapOf("ok" to false, "unavailable" to true,
                    "message" to "Workflow.$method 需要宿主工作流数据，当前运行环境未注入")
            )
        }
        fun str(idx: Int): String? = (args.getOrNull(idx) as? JsonPrimitive)?.content
        fun obj(idx: Int): JsonObject? = (args.getOrNull(idx) as? JsonObject)
        return try {
            when (method) {
                "getAll", "list" -> {
                    val workflows = runBlocking { repo.loadAll() }
                    Json.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("success", true)
                        put("message", "找到 ${workflows.size} 个工作流")
                        put("workflows", JsonArray(workflows.map { workflowToJson(it) }))
                    })
                }
                "get" -> {
                    val id = str(0) ?: return Json.encodeToString(opErr("get 需要 id"))
                    val wf = runBlocking { repo.loadWorkflow(id) }
                    if (wf == null) {
                        Json.encodeToString(mapOf("ok" to false, "success" to false, "message" to "工作流不存在：$id"))
                    } else {
                        Json.encodeToString(buildJsonObject {
                            put("ok", true)
                            put("success", true)
                            put("workflow", workflowToJson(wf))
                        })
                    }
                }
                "create" -> {
                    val params = obj(0)
                    val name = (params?.get("name") as? JsonPrimitive)?.content
                        ?: return Json.encodeToString(opErr("create 需要 name"))
                    val description = (params?.get("description") as? JsonPrimitive)?.content ?: ""
                    val wf = runBlocking { repo.create(name = name, description = description) }
                    Json.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("success", true)
                        put("message", "已创建工作流 ${wf.name}")
                        put("workflow", workflowToJson(wf))
                    })
                }
                "update" -> {
                    val id = str(0) ?: return Json.encodeToString(opErr("update 需要 id"))
                    val params = obj(1)
                    val existing = runBlocking { repo.loadWorkflow(id) }
                    if (existing == null) {
                        return Json.encodeToString(mapOf("ok" to false, "success" to false, "message" to "工作流不存在：$id"))
                    }
                    val name = (params?.get("name") as? JsonPrimitive)?.content ?: existing.name
                    val description = (params?.get("description") as? JsonPrimitive)?.content ?: existing.description
                    val updated = runBlocking { repo.save(existing.copy(name = name, description = description)) }
                    Json.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("success", true)
                        put("message", "已更新工作流 ${updated.name}")
                        put("workflow", workflowToJson(updated))
                    })
                }
                "delete" -> {
                    val id = str(0) ?: return Json.encodeToString(opErr("delete 需要 id"))
                    val ok = runBlocking { repo.delete(id) }
                    Json.encodeToString(if (ok) {
                        mapOf("ok" to true, "success" to true, "message" to "已删除工作流 $id")
                    } else {
                        mapOf("ok" to false, "success" to false, "message" to "工作流不存在：$id")
                    })
                }
                else -> Json.encodeToString(
                    mapOf("ok" to false, "unavailable" to true, "message" to "RikkaHub 暂不支持 Workflow.$method")
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "workflow call error: $method", e)
            Json.encodeToString(
                mapOf("ok" to false, "message" to "执行 Workflow.$method 失败：${e.message ?: "未知错误"}")
            )
        }
    }

    private fun workflowToJson(wf: Workflow): JsonObject = buildJsonObject {
        put("id", wf.id)
        put("name", wf.name)
        put("description", wf.description)
        put("createdAt", wf.createdAt)
        put("updatedAt", wf.updatedAt)
        put("steps", JsonArray(wf.steps.map { step ->
            buildJsonObject {
                put("id", step.id)
                put("name", step.name)
                put("type", step.type.name.lowercase())
            }
        }))
    }

    // ---------- Memory ----------

    /** Memory.* 工具：桥接 RikkaHub 记忆引擎（query/create/update/deleteMemory）。 */
    private fun handleMemory(method: String, args: List<JsonElement>): String {
        val repo = memoryRepository
        if (repo == null) {
            return Json.encodeToString(
                mapOf("ok" to false, "unavailable" to true,
                    "message" to "Memory.$method 需要宿主记忆数据，当前运行环境未注入")
            )
        }
        fun str(idx: Int): String? = (args.getOrNull(idx) as? JsonPrimitive)?.content
        fun obj(idx: Int): JsonObject? = (args.getOrNull(idx) as? JsonObject)
        fun intArg(o: JsonObject?, key: String): Int? =
            (o?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return try {
            when (method) {
                "query", "recall" -> {
                    val params = obj(0) ?: return Json.encodeToString(opErr("query 需要参数"))
                    val query = (params["query"] as? JsonPrimitive)?.content
                        ?: return Json.encodeToString(opErr("query 缺少 query"))
                    val assistantId = (params["assistant_id"] as? JsonPrimitive)?.content
                        ?: (params["assistantId"] as? JsonPrimitive)?.content
                        ?: MemoryRepository.GLOBAL_MEMORY_ID
                    val limit = intArg(params, "limit") ?: 8
                    val memories = runBlocking {
                        repo.recallMemories(
                            query = query,
                            assistantId = assistantId,
                            conversationId = (params["conversation_id"] as? JsonPrimitive)?.content,
                            limit = limit.coerceIn(1, 50),
                        )
                    }
                    Json.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("success", true)
                        put("message", "找到 ${memories.size} 条相关记忆")
                        put("memories", JsonArray(memories.map { memoryToJson(it) }))
                    })
                }
                "create", "store" -> {
                    val params = obj(0) ?: return Json.encodeToString(opErr("create 需要参数"))
                    val content = (params["content"] as? JsonPrimitive)?.content
                        ?: return Json.encodeToString(opErr("create 缺少 content"))
                    val assistantId = (params["assistant_id"] as? JsonPrimitive)?.content
                        ?: (params["assistantId"] as? JsonPrimitive)?.content
                        ?: MemoryRepository.GLOBAL_MEMORY_ID
                    val memory = runBlocking {
                        repo.storeMemory(
                            assistantId = assistantId,
                            content = content,
                            summary = (params["summary"] as? JsonPrimitive)?.content,
                            source = "script",
                        )
                    }
                    Json.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("success", true)
                        put("message", "已创建记忆 ${memory.id}")
                        put("memory", memoryToJson(memory))
                    })
                }
                "update" -> {
                    val params = obj(0) ?: return Json.encodeToString(opErr("update 需要参数"))
                    val id = intArg(params, "id")
                        ?: return Json.encodeToString(opErr("update 缺少 id"))
                    val content = (params["content"] as? JsonPrimitive)?.content
                        ?: return Json.encodeToString(opErr("update 缺少 content"))
                    val memory = runBlocking {
                        repo.updateMemory(
                            id = id,
                            content = content,
                            summary = (params["summary"] as? JsonPrimitive)?.content,
                        )
                    }
                    Json.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("success", true)
                        put("message", "已更新记忆 $id")
                        put("memory", memoryToJson(memory))
                    })
                }
                "deleteMemory", "delete" -> {
                    val id = runCatching {
                        val params = obj(0)
                        if (params != null) {
                            intArg(params, "id")
                        } else {
                            (args.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                        }
                    }.getOrNull()
                    if (id == null) {
                        Json.encodeToString(opErr("deleteMemory 缺少 id"))
                    } else {
                        runBlocking { repo.deleteMemory(id) }
                        Json.encodeToString(mapOf("ok" to true, "success" to true, "message" to "已删除记忆 $id"))
                    }
                }
                else -> Json.encodeToString(
                    mapOf("ok" to false, "unavailable" to true, "message" to "RikkaHub 暂不支持 Memory.$method")
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "memory call error: $method", e)
            Json.encodeToString(
                mapOf("ok" to false, "message" to "执行 Memory.$method 失败：${e.message ?: "未知错误"}")
            )
        }
    }

    private fun memoryToJson(m: AssistantMemory): JsonObject = buildJsonObject {
        put("id", m.id)
        put("content", m.content)
        put("summary", m.summary.orEmpty())
        put("target", m.target)
        put("source", m.source.orEmpty())
        put("updatedAt", m.updatedAt)
        put("score", m.score)
    }

    // ---------- Chat ----------

    /**
     * Chat.* 工具：经 [ScriptChatBridge] 映射到 RikkaHub 本地会话。
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
                "sendMessage", "sendMessageStreaming" -> {
                    val params = obj(0) ?: return Json.encodeToString(opErr("$method 需要参数"))
                    val chatId = (params["chat_id"] as? JsonPrimitive)?.content
                        ?: (params["chatId"] as? JsonPrimitive)?.content
                        ?: return Json.encodeToString(opErr("$method 缺少 chat_id"))
                    val content = (params["content"] as? JsonPrimitive)?.content
                        ?: (params["message"] as? JsonPrimitive)?.content
                        ?: return Json.encodeToString(opErr("$method 缺少 content"))
                    val role = (params["role"] as? JsonPrimitive)?.content ?: "user"
                    val messageId = bridge.sendMessage(chatId, content, role)
                    if (messageId == null) {
                        Json.encodeToString(
                            mapOf("ok" to false, "success" to false,
                                "message" to "发送失败：会话不存在或内容为空")
                        )
                    } else {
                        Json.encodeToString(buildJsonObject {
                            put("ok", true)
                            put("success", true)
                            put("message", "已写入${if (role == "assistant") "助手" else "用户"}消息")
                            put("chatId", chatId)
                            put("messageId", messageId)
                        })
                    }
                }
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
private class ScriptCalcExpr(private val s: String) {
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

/** 用 Android 通知通道发送 脚本触发的系统通知 */
internal object ScriptNotifier {
    private const val CHANNEL_ID = "script_tools"
    private var prepared = false

    fun show(context: Context, title: String, body: String) {
        val titleSafe = title.ifBlank { "脚本通知" }
        val bodySafe = body.ifBlank { "" }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "脚本工具",
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
