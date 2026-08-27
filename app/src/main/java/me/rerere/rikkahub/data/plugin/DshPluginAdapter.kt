package me.rerere.rikkahub.data.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.script.ScriptToolDef
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 解析出的 DSH 插件仓库引用 */
data class DshRepoRef(
    val owner: String,
    val repo: String,
    val ref: String = "main",
) {
    /** 仓库内唯一标识（owner/repo），用于生成不冲突的插件 id */
    val slug: String get() = "$owner-$repo".lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

/**
 * DeepSeek Harness（DSH）插件仓库适配器。
 * DSH 插件以 GitHub 仓库分发（package.json 声明 dsh.bundle，apply(ctx) 注册工具/技能/UI），
 * RikkaHub 无 Cordis/Node 宿主运行时，本适配器提取其中可迁移的能力：
 * - skills 资源（SKILL.md）→ skill 型插件（systemPrompt 注入，完整可用）
 * - defineTool 工具定义 → 提示词型插件（列出能力清单供 AI 参考）
 * - npm 包 bin CLI → 工作区命令能力（工作区已内置 Node.js/npm，AI 可经终端真实执行）
 * 纯 UI 增强 / 宿主 API 深度依赖的插件无法迁移时，README 兜底为知识参考型插件。
 */
class DshPluginAdapter(
    private val httpClient: OkHttpClient,
    private val context: android.content.Context? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 支持 github:owner/repo#ref、https://github.com/owner/repo(/tree/ref)(.git)、owner/repo */
    internal fun parseRepoRef(input: String): DshRepoRef? {
        var text = input.trim().trimEnd('/')
        if (text.isBlank()) return null
        text = text.removePrefix("github:")
        text = text.substringAfter("github.com/")
        if (text.startsWith("http")) return null
        // 先剥离 "#ref" 后缀（dsh plugin add "github:owner/repo#ref" 形式）
        var ref: String? = null
        val hashIndex = text.lastIndexOf('#')
        if (hashIndex >= 0) {
            ref = text.substring(hashIndex + 1).takeIf { it.isNotBlank() }
            text = text.substring(0, hashIndex)
        }
        // .../tree/<ref> 形式提取分支；.git 后缀去除
        val treeIndex = text.indexOf("/tree/")
        if (treeIndex >= 0) {
            val repoPart = text.substring(0, treeIndex).removeSuffix(".git")
            val treeRef = text.substring(treeIndex + "/tree/".length).trimEnd('/')
                .substringBefore('/')
                .takeIf { it.isNotBlank() }
            ref = ref ?: treeRef
            val parts = repoPart.split('/')
            if (parts.size < 2) return null
            return DshRepoRef(parts[0], parts[1], ref ?: "main")
        }
        val parts = text.removeSuffix(".git").split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return DshRepoRef(owner = parts[0], repo = parts[1], ref = ref ?: "main")
    }

    /** 拉取仓库 zip 包并转换为 RikkaHub 插件 zip 字节 */
    suspend fun fetchAsZip(repoRef: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val ref = parseRepoRef(repoRef) ?: error("无法识别的仓库地址：$repoRef")
            val bytes = download(
                "https://codeload.github.com/${ref.owner}/${ref.repo}/zip/${ref.ref}"
            )
            val tempRoot = Files.createTempDirectory("dsh-").toFile()
            try {
                PluginManager.unzipTo(bytes, tempRoot)
                val root = locateRepoRoot(tempRoot, ref.repo)
                    ?: error("仓库内容为空或无法解压")
                val info = convertRepo(root, ref)
                // 面板优先：带 client 入口的仓库生成可交互运行壳，否则退回纯文档页
                val clientEntry = findClientEntry(root)
                val docsPage = buildDocsPage(root, ref)
                val indexHtml = if (clientEntry != null) {
                    buildPanelPage(ref, runCatching { clientEntry.readText() }.getOrDefault(""), docsPage)
                } else {
                    docsPage
                }
                convertToZip(
                    info,
                    indexHtml = indexHtml,
                    clientJs = clientEntry?.let { runCatching { it.readText() }.getOrNull() },
                )
            } finally {
                runCatching { tempRoot.deleteRecursively() }
            }
        }
    }

    /**
     * 探测客户端 UI 入口：dsh.plugin.json 的 client.main 声明优先，
     * 其余按社区常见打包路径兜底。
     */
    internal fun findClientEntry(root: File): File? {
        val declared = root.resolve("dsh.plugin.json").takeIf { it.isFile }
            ?.let { file ->
                runCatching {
                    (json.parseToJsonElement(file.readText()).jsonObject["client"] as? JsonObject)
                        ?.get("main") as? JsonPrimitive
                }.getOrNull()?.contentOrNull
            }
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
        val candidates = listOfNotNull(
            declared,
            "lib/client.js", "client/client.js", "dist/client.js", "client.js",
        )
        return candidates.firstNotNullOfOrNull { rel ->
            root.resolve(rel).normalizeFile().takeIf { it.isFile && it.length() in 1..MAX_CLIENT_JS_BYTES }
        }
    }

    /** java.io.File 无 canonical 开销的规范化（消除 "./" 与冗余段），便于路径相等比较 */
    private fun File.normalizeFile(): File {
        val segments = absolutePath.split('/').filter { it.isNotEmpty() && it != "." }
        val prefix = if (absolutePath.startsWith("/")) "/" else ""
        return File(prefix + segments.joinToString("/"))
    }

    /**
     * 纯逻辑：把解压后的 DSH 仓库目录转换为 PluginInfo（供单元测试与复用）。
     * 转换优先级：SKILL.md 技能 > defineTool 工具定义 > npm CLI 工作区命令 > README 说明；
     * 能力段落之外始终附加工作区命令说明与文档兜底，最大化可用性。
     */
    internal fun convertRepo(root: File, ref: DshRepoRef): PluginInfo {
        val pkg = root.resolve("package.json").takeIf { it.isFile }
            ?.let { runCatching { json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
        fun pkgString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            (pkg?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
        // dsh.plugin.json 清单兜底：package.json 缺失或字段缺失时回退读取 DSH 自有清单
        val manifest = root.resolve("dsh.plugin.json").takeIf { it.isFile }
            ?.let { runCatching { json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
        fun manifestString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            (manifest?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
        val name = pkgString("displayName", "name") ?: manifestString("name", "title") ?: ref.repo
        val description = pkgString("description") ?: manifestString("description", "desc").orEmpty()
        val version = pkgString("version") ?: manifestString("version") ?: "1.0.0"
        val author = pkgString("author") ?: manifestString("author") ?: ref.owner
        val npmPackage = (pkg?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("github:") }
        val workspaceCommandHint = buildWorkspaceCommandHint(npmPackage, root)
        // 结构化声明工作区 CLI 依赖: 供插件列表环境提醒/一键补全/预装使用
        val npmPackages = if (workspaceCommandHint.isNotBlank()) {
            listOfNotNull(npmPackage)
        } else {
            emptyList()
        }

        // 1. skills 资源：根级或 skills/** 的 SKILL.md 合并为技能型插件
        val skillFiles = root.walkTopDown()
            .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
            .sortedBy { it.relativeTo(root).path }
            .toList()
        if (skillFiles.isNotEmpty()) {
            val prompt = skillFiles.joinToString("\n\n---\n\n") { file ->
                runCatching { file.readText().trim() }.getOrDefault("")
            }.take(PluginManager.MAX_SYSTEM_PROMPT_LEN)
            return PluginInfo(
                id = "dsh-${ref.slug}",
                name = name,
                version = version,
                description = description.ifBlank { "DeepSeek Harness 技能包 $name" },
                author = author,
                category = "skill",
                repository = "https://github.com/${ref.owner}/${ref.repo}",
                systemPrompt = (prompt + workspaceCommandHint).take(PluginManager.MAX_SYSTEM_PROMPT_LEN),
                type = PluginCategories.TYPE_SKILL,
                tags = listOf("dsh", "skill"),
                npmPackages = npmPackages,
                extensionPoints = docsSidebarEntry(
                    PluginInfo(id = "dsh-${ref.slug}", name = name, version = "1.0.0",
                        repository = "https://github.com/${ref.owner}/${ref.repo}")
                ) ?: PluginExtensionPoints(),
            )
        }

        // 2. defineTool 工具定义：静态扫描源码中的工具声明转为能力提示词插件
        val tools = extractDefineTools(root)
        if (tools.isNotEmpty()) {
            val prompt = buildString {
                appendLine("该插件来自 DeepSeek Harness（DSH）生态「$name」。")
                if (description.isNotBlank()) appendLine("简介：$description")
                appendLine("提供以下能力定义，可结合自身工具与环境按需参考执行：")
                tools.forEach { tool ->
                    append("- ").append(tool.name)
                    if (tool.description.isNotBlank()) append("：").append(tool.description.take(200))
                    appendLine()
                }
                append("注：该插件原生依赖 DSH Node 宿主运行时，RikkaHub 以提示词形式承载其能力定义。")
            }.trim().take(PluginManager.MAX_SYSTEM_PROMPT_LEN)
            return PluginInfo(
                id = "dsh-${ref.slug}",
                name = name,
                version = version,
                description = description.ifBlank { "DeepSeek Harness 插件 $name" },
                author = author,
                category = "general",
                repository = "https://github.com/${ref.owner}/${ref.repo}",
                systemPrompt = (prompt + workspaceCommandHint).take(PluginManager.MAX_SYSTEM_PROMPT_LEN),
                type = PluginCategories.TYPE_PLUGIN,
                tags = listOf("dsh"),
                npmPackages = npmPackages,
                extensionPoints = docsSidebarEntry(
                    PluginInfo(id = "dsh-${ref.slug}", name = name, version = "1.0.0",
                        repository = "https://github.com/${ref.owner}/${ref.repo}")
                ) ?: PluginExtensionPoints(),
            )
        }

        // 3. npm CLI 工具：无 skills/defineTool 但发布为 npm 包时，注册为工作区命令能力插件
        if (workspaceCommandHint.isNotBlank()) {
            val prompt = buildString {
                appendLine("该插件来自 DeepSeek Harness（DSH）生态「$name」。")
                if (description.isNotBlank()) appendLine("简介：$description")
                appendLine("它以 npm 命令行工具形式提供，RikkaHub 已将其接入工作区终端能力。")
                append(workspaceCommandHint.trim())
            }.trim().take(PluginManager.MAX_SYSTEM_PROMPT_LEN)
            return PluginInfo(
                id = "dsh-${ref.slug}",
                name = name,
                version = version,
                description = description.ifBlank { "DeepSeek Harness CLI 工具 $name" },
                author = author,
                category = "tools",
                repository = "https://github.com/${ref.owner}/${ref.repo}",
                systemPrompt = prompt,
                type = PluginCategories.TYPE_PLUGIN,
                tags = listOf("dsh", "cli"),
                npmPackages = npmPackages,
                extensionPoints = docsSidebarEntry(
                    PluginInfo(id = "dsh-${ref.slug}", name = name, version = "1.0.0",
                        repository = "https://github.com/${ref.owner}/${ref.repo}")
                ) ?: PluginExtensionPoints(),
            )
        }

        // 4. 兜底 README 说明型 / 纯 UI 面板型：不再拒装，转为文档承载型插件，
        //    面板入口在侧边栏可见（web/index.html 由 buildDocsPage 生成）
        val readme = root.walkTopDown()
            .filter { it.isFile && it.name.equals("README.md", ignoreCase = true) }
            .firstOrNull()
            ?.let { runCatching { it.readText().trim() }.getOrNull() }
            .orEmpty()
        return PluginInfo(
            id = "dsh-${ref.slug}",
            name = name,
            version = version,
            description = description.ifBlank { "DeepSeek Harness 插件 $name" },
            author = author,
            category = if (readme.length >= MIN_README_LEN) "knowledge" else "ui",
            repository = "https://github.com/${ref.owner}/${ref.repo}",
            systemPrompt = (
                if (readme.length >= MIN_README_LEN) {
                    "以下是 DeepSeek Harness 插件「$name」的说明文档：\n\n${readme.take(PluginManager.MAX_SYSTEM_PROMPT_LEN)}"
                } else {
                    "该插件来自 DeepSeek Harness（DSH）生态「$name」。" +
                        (if (description.isNotBlank()) "简介：$description。" else "") +
                        "原生为 DSH Web UI 增强/面板类插件，RikkaHub 以文档与仓库链接形式承载，" +
                        "完整功能请通过面板入口查看其 GitHub 仓库。"
                }
                ).take(PluginManager.MAX_SYSTEM_PROMPT_LEN),
            type = PluginCategories.TYPE_SKILL,
            tags = listOf("dsh", if (readme.length >= MIN_README_LEN) "docs" else "ui"),
            extensionPoints = docsSidebarEntry(
                PluginInfo(id = "dsh-${ref.slug}", name = name, version = "1.0.0",
                    repository = "https://github.com/${ref.owner}/${ref.repo}")
            ) ?: PluginExtensionPoints(),
        )
    }

    /**
     * 工作区命令提示：插件发布为 npm 包且带 bin 入口时，
     * 生成供 AI 在工作区终端真实执行的命令说明（工作区内置 Node.js/npm）。
     */
    internal fun buildWorkspaceCommandHint(npmPackage: String?, root: File): String {
        val pkg = root.resolve("package.json").takeIf { it.isFile }
            ?.let { runCatching { json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
            ?: return ""
        val bin = pkg["bin"] ?: return ""
        // npm 规范：bin 为对象时 key 即命令名；为字符串时命令名取包名末段（值为入口路径）
        val defaultBinName = npmPackage?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val binNames = when (bin) {
            is JsonObject -> bin.keys.toList()
            is JsonPrimitive -> listOfNotNull(defaultBinName)
            else -> emptyList()
        }
        if (binNames.isEmpty() || npmPackage.isNullOrBlank()) return ""
        return buildString {
            appendLine()
            appendLine()
            appendLine("## 工作区命令能力")
            appendLine("本插件提供 npm 命令行工具，可在 RikkaHub 工作区终端中直接执行（需先完成 Node.js 安装）：")
            binNames.take(MAX_BIN_HINTS).forEach { binName ->
                appendLine("- `$binName`：`npx -y $npmPackage`（参数原样追加，例如 `npx -y $npmPackage --help`）")
            }
            append("在用户请求相关能力而当前环境缺少对应工具时，优先尝试上述命令。")
        }
    }

    /**
     * 面板运行壳：shim DSH 客户端宿主（window.__ModuleLoader__ + cordis ctx +
     * react/react-dom require），在 WebView 中真实执行插件 client.js 并挂载其 React UI。
     * 挂载失败或超时时降级为 README 文档页，保证"看得见"。
     */
    internal fun buildPanelPage(ref: DshRepoRef, clientJs: String, docsPageHtml: String): String {
        val docsFolded = docsPageHtml
            .substringAfter("<body>", "")
            .substringBefore("</body>")
            .let { """<section id="docs-fallback" style="display:none">$it</section>""" }
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("""<html lang="zh"><head><meta charset="utf-8">""")
            appendLine("""<meta name="viewport" content="width=device-width, initial-scale=1">""")
            appendLine("<title>${escapeHtml(ref.repo)}</title>")
            appendLine(DOCS_PAGE_CSS)
            appendLine(PANEL_PAGE_CSS)
            // 运行时优先本地随包分发（零网络依赖），加载失败才回退 CDN
            appendLine("""<script src="./react.production.min.js"
                onerror="this.onerror=null;var s=document.createElement('script');s.src='https://cdn.jsdelivr.net/npm/react@18.3.1/umd/react.production.min.js';document.head.appendChild(s)"></script>""")
            appendLine("""<script src="./react-dom.production.min.js"
                onerror="this.onerror=null;var s=document.createElement('script');s.src='https://cdn.jsdelivr.net/npm/react-dom@18.3.1/umd/react-dom.production.min.js';document.head.appendChild(s)"></script>""")
            appendLine("<script>$PANEL_HOST_SHIM</script>")
            appendLine("</head><body>")
            appendLine("""<div id="status" class="meta">正在启动插件面板…</div>""")
            appendLine("""<div id="panel-root"></div>""")
            appendLine(docsFolded)
            appendLine("""<script src="./plugin.client.js"></script>""")
            appendLine("<script>window.__dshPanelMountAll__ && window.__dshPanelMountAll__();</script>")
            appendLine("</body></html>")
        }.replace("__RAW_BASE__", "https://raw.githubusercontent.com/${ref.owner}/${ref.repo}/${ref.ref}/")
    }

    /**
     * 宿主 shim 脚本：模块加载器、cordis ctx、react 三件套 require 映射、DSH 生态
     * 通用包适配桩与延迟挂载机制。mountAll 在 client.js 注册后统一调用 apply 挂载面板 DOM。
     *
     * 兼容策略：
     * - require() 对 DSH 生态常用包（@deepseek-ai/dsh-client-ui-slots / -components / dsh-base / log 等）
     *   返回可继续调用的适配桩，而非立即抛错；unknown 模块返回链式 no-op Proxy。
     * - ctx.get() 覆盖 slots/timer 之外更多宿主服务（logger/config/event/…），全部安全空转。
     * - slots 注册支持异步：apply 结束后在 0.3s/1s/3s/8s 多次复查插槽表，迟到注册也能挂载。
     * - 任一步失败都会把真实错误写入 status，而不是只有一句笼统提示。
     */
    internal val PANEL_HOST_SHIM = """
window.__ModuleLoader__ = (function () {
    var pending = [];
    var slotStore = {};
    var mounted = false;
    var pluginErrors = [];
    var missingList = [];
    var missingSeen = {};
    var globalModule = { exports: {}, mounted: false };

    function recordGlobalMissing(name) {
        var k = String(name);
        if (!missingSeen[k]) { missingSeen[k] = true; missingList.push(k); }
    }

    function noopModule(recordMissing) {
        var fn = function () { return undefined; };
        var proxy = new Proxy(fn, {
            get: function (t, p) {
                if (p === Symbol.toStringTag) return 'Module';
                if (p === 'default') return t;
                if (p === 'then') return undefined;
                return t;
            },
            apply: function (t, self, args) { return t; },
            construct: function (t, args) { return t; },
            has: function () { return true; },
            set: function () { return true; }
        });
        return proxy;
    }

    function makeRequire(recordMissing) {
        function requireImpl(name) {
            var key = String(name || '').toLowerCase();
            if (key === 'react') {
                if (!window.React) throw new Error('React not loaded');
                return window.React;
            }
            if (key === 'react-dom' || key === 'react-dom/client' || key === 'react-dom/server') {
                if (!window.ReactDOM) throw new Error('ReactDOM not loaded');
                return window.ReactDOM;
            }
            if (key === 'react/jsx-runtime' || key === 'react/jsx-dev-runtime') {
                var R = window.React;
                if (!R) throw new Error('React not loaded');
                function jsx(type, props, keyArg) {
                    if (keyArg !== undefined) {
                        props = Object.assign({}, props, { key: keyArg });
                    }
                    return R.createElement(type, props);
                }
                jsx.Fragment = R.Fragment;
                return { jsx: jsx, jsxs: jsx, jsxDEV: jsx, Fragment: R.Fragment };
            }
            // DSH 生态常用包适配桩：返回可写可调的对象（见 slotsExport / componentsExport）
            if (key === '@deepseek-ai/dsh-client-ui-slots' || key === '@deepseek-ai/dsh-slots') {
                return slotsExport;
            }
            if (key === '@deepseek-ai/dsh-client-ui-components' || key === '@deepseek-ai/dsh-client-ui' ||
                key === '@deepseek-ai/dsh-ui' || key === '@deepseek-ai/dsh-client-ui-react') {
                return componentsExport();
            }
            if (key === '@deepseek-ai/dsh-client' || key === '@deepseek-ai/dsh-client-ui-tab') {
                return dshClientExport();
            }
            if (key === '@deepseek-ai/dsh-base' || key === '@deepseek-ai/dsh-utils' ||
                key === '@deepseek-ai/dsh-command-compact') {
                return baseExport();
            }
            if (key === '@deepseek-ai/log' || key === '@deepseek-ai/dsh-log') {
                return loggerExport();
            }
            if (key.indexOf('./') === 0 || key.indexOf('../') === 0) {
                if (recordMissing) recordMissing(name);
                return noopModule();
            }
            if (recordMissing) recordMissing(name);
            return noopModule();
        }
        return requireImpl;
    }

    // ---------- ctx 宿主服务 ----------
    function loggerService() {
        var levels = ['trace', 'debug', 'info', 'warn', 'error', 'fatal'];
        var svc = { level: 'info', bind: function () { return svc; }, child: function () { return svc; } };
        levels.forEach(function (l) {
            svc[l] = function () {
                var args = Array.prototype.slice.call(arguments);
                if (l === 'warn' || l === 'error' || l === 'fatal') {
                    try { console[l == 'fatal' ? 'error' : l].apply(console, ['[dsh-plugin]'].concat(args)); } catch (e) {}
                }
            };
        });
        return svc;
    }

    function configService() {
        var store = {};
        return {
            get: function (key, fallback) { return store[key] !== undefined ? store[key] : fallback; },
            set: function (key, val) { store[key] = val; },
            has: function (key) { return store[key] !== undefined; },
            keys: function () { return Object.keys(store); },
            getConfig: function () { return store; },
            setConfig: function (c) { if (c && typeof c === 'object') store = c; }
        };
    }

    function eventService() {
        var handlers = {};
        return {
            on: function (name, fn) { (handlers[name] = handlers[name] || []).push(fn); return function () {}; },
            once: function (name, fn) {
                var wrapped = function () { off(name, wrapped); return fn.apply(null, arguments); };
                function off(n, f) { if (handlers[n]) handlers[n] = handlers[n].filter(function (h) { return h !== f; }); }
                on(name, wrapped);
            },
            off: function (name, fn) {
                if (handlers[name]) handlers[name] = handlers[name].filter(function (h) { return h !== fn; });
            },
            emit: function (name) {
                var rest = Array.prototype.slice.call(arguments, 1);
                (handlers[name] || []).slice().forEach(function (h) { try { h.apply(null, rest); } catch (e) {} });
            }
        };
    }

    function httpService() {
        function req(method) {
            return function (url, opts) {
                return Promise.reject(new Error('[dsh-shim] 网络能力不可用(宿主未提供 http 服务): ' + method + ' ' + url));
            };
        }
        return { get: req('GET'), post: req('POST'), put: req('PUT'), delete: req('DELETE'), head: req('HEAD') };
    }

    function assetService() {
        return {
            url: function (name) { return name; },
            base: function () { return './'; },
            locale: function () { return 'zh-CN'; },
            assets: function () { return {}; }
        };
    }

    function modelService() {
        return {
            runtime: function () { return 'standard'; },
            request: function (opts) { return { stream: false, content: '' }; },
            stream: function (opts) { return { then: function () {}, [Symbol.asyncIterator]() { return { next: function () { return Promise.resolve({ done: true }); } }; } }; },
            start: function (opts) { return Promise.resolve({}); },
            stop: function () { return Promise.resolve(); }
        };
    }

    function callableService(fn) {
        return fn;
    }

    function makeCtx() {
        return {
            effect: function (fn) { try { return fn(this); } catch (e) { console.error(e); } },
            get: function (name) {
                var key = String(name || '').toLowerCase();
                if (key === 'slots') return slotsApi;
                if (key === 'timer') return timerApi;
                if (key === 'logger' || key === 'log') return loggerService();
                if (key === 'config' || key === 'conf') return configService();
                if (key === 'event' || key === 'events' || key === 'bus') return eventService();
                if (key === 'http' || key === 'axios' || key === 'got') return httpService();
                if (key === 'assets' || key === 'asset') return assetService();
                if (key === 'model' || key === 'models' || key === 'llm' || key === 'ai') return modelService();
                return new Proxy(function () {}, {
                    get: function (t, p) {
                        if (p === Symbol.toStringTag || p === 'default' || p === 'then') return t;
                        return function () {};
                    },
                    has: function () { return true; }
                });
            },
            set: function () {},
            on: function () { return this; },
            emit: function () {},
            inject: function (slotName, factory) {
                try { factory(); } catch (e) { console.error('[dsh-shim] ctx.inject failed', slotName, e); }
            },
            register: function (config, render) { slotsApi.register(config, render); }
        };
    }

    // ---------- 生态包适配桩 ----------
    var slotsApi = {
        register: function (config, render) {
            var entry = { config: config || {}, render: render };
            var slotName = (config && config.name) || (config && config.slot) || 'unnamed';
            if (!slotStore[slotName]) slotStore[slotName] = [];
            slotStore[slotName].push(entry);
            scheduleRender();
            return entry;
        },
        inject: function (slotName, factory) {
            try { factory(); } catch (e) { console.error('[dsh-shim] inject failed', slotName, e); }
        },
        has: function (slotName) { return !!slotStore[slotName] && slotStore[slotName].length > 0; },
        list: function () { return Object.keys(slotStore); }
    };

    var slotsExport = Object.assign(function () { return slotsApi; }, slotsApi);
    slotsExport.default = slotsExport;
    slotsExport.register = slotsApi.register;
    slotsExport.inject = slotsApi.inject;

    function componentsExport() {
        var R = window.React;
        var base = function (props) { return R ? R.createElement('div', props) : null; };
        var proxy = new Proxy(base, {
            get: function (t, p) {
                if (p === Symbol.toStringTag || p === 'default' || p === 'then') return t;
                if (p === 'Fragment') return (R && R.Fragment) || 'div';
                // 对象式导出成员（样式/常量等）
                return t;
            },
            construct: function (t, args) { return R ? R.createElement('div', args[0]) : null; },
            has: function () { return true; }
        });
        proxy.default = proxy;
        return proxy;
    }

    function dshClientExport() {
        var exp = {
            ready: Promise.resolve(),
            run: function () { return Promise.resolve(); },
            version: '0.1.1-rc.2 (RikkaHub shim)',
            slots: slotsApi,
            client: { version: '0.1.1-rc.2' }
        };
        exp.default = exp;
        return exp;
    }

    function baseExport() {
        var exp = {
            Json: { parse: function () { return {}; }, stringify: function () { return '{}'; }, deepClone: function (o) { return o; } },
            deepmerge: function () { return {}; },
            sleep: function () { return Promise.resolve(); },
            isBrowser: function () { return true; },
            isNode: function () { return false; },
            noop: function () {},
        };
        var proxy = new Proxy(function () { return undefined; }, {
            get: function (t, p) {
                if (p in exp) return exp[p];
                if (p === Symbol.toStringTag || p === 'default' || p === 'then') return t;
                return t;
            },
            has: function () { return true; }
        });
        proxy.default = proxy;
        return proxy;
    }

    function loggerExport() {
        var svc = loggerService();
        svc.default = svc;
        var proxy = new Proxy(function () {}, {
            get: function (t, p) {
                if (p in svc) return svc[p];
                if (p === Symbol.toStringTag || p === 'default' || p === 'then') return t;
                return function () {};
            },
            apply: function () { return svc; },
            has: function () { return true; }
        });
        return proxy;
    }

    var timerApi = {
        setInterval: function (fn, ms) {
            return setInterval(function () { try { fn(); } catch (e) {} }, ms || 1000);
        },
        setTimeout: function (fn, ms) {
            return setTimeout(function () { try { fn(); } catch (e) {} }, ms || 0);
        },
        clearInterval: function (id) { clearInterval(id); },
        clearTimeout: function (id) { clearTimeout(id); },
        every: function (ms, fn) { return this.setInterval(fn, ms); }
    };

    // ---------- 渲染与状态 ----------
    function setStatus(text) {
        var el = document.getElementById('status');
        if (el) el.textContent = text;
    }

    function showDocs() {
        var docs = document.getElementById('docs-fallback');
        if (docs) docs.style.display = '';
    }

    function renderSlots() {
        var root = document.getElementById('panel-root');
        if (!root) return;
        var names = Object.keys(slotStore);
        if (!names.length) return;
        var R = window.React, D = window.ReactDOM;
        if (!R || !D) return;
        try {
            var all = R.createElement('div', { className: 'dsh-slot-container' },
                names.map(function (name) {
                    var entries = slotStore[name];
                    return R.createElement('div', { key: name, className: 'dsh-slot-section' },
                        R.createElement('div', { className: 'dsh-slot-title' }, name),
                        entries.map(function (entry, i) {
                            var inner = null;
                            try { inner = entry.render(); } catch (e) { inner = null; }
                            return R.createElement('div', { key: i, className: 'dsh-slot-content' }, inner);
                        })
                    );
                })
            );
            if (D.createRoot) {
                D.createRoot(root).render(all);
            } else {
                D.render(all, root);
            }
        } catch (e) {
            console.error('[dsh-shim] render failed', e);
            setStatus('面板渲染失败：' + (e && e.message ? e.message : e) + '（已显示文档）');
            showDocs();
        }
    }

    var recheckTimer = null;
    var recheckCount = 0;
    function scheduleRender() {
        // 注册在异步 apply 结束后到达：延迟到 React 就绪再渲染
        setTimeout(function () {
            if (window.React && window.ReactDOM) { mounted = true; renderSlots(); refreshStatus(); }
        }, 50);
    }

    function refreshStatus() {
        var n = Object.keys(slotStore).length;
        if (n > 0) {
            setStatus('面板已加载 (' + n + ' 个插槽)');
        }
    }

    function reportResult(applied, errors) {
        window.__dshShimErrors__ = errors;
        var hasContent = Object.keys(slotStore).length > 0;
        if (hasContent) {
            mounted = true;
            renderSlots();
            setTimeout(function () { setStatus('面板已加载 (' + Object.keys(slotStore).length + ' 个插槽)'); }, 200);
            return;
        }
        if (applied > 0) {
            // 可能异步注册：稍后复查
            scheduleRecheck();
            return;
        }
        var msg = '该插件未导出可运行的界面（apply 注册失败）';
        if (errors.length) {
            msg = '插件界面加载失败（' + errors.length + '）—— 已显示文档';
            if (window.__dshShimMissing__ && window.__dshShimMissing__.length) {
                msg += '；缺失宿主模块：' + window.__dshShimMissing__.slice(0, 3).join(', ');
            }
        }
        setStatus(msg);
        showDocs();
    }

    function scheduleRecheck() {
        var delays = [300, 1000, 3000, 8000];
        if (recheckCount >= delays.length) { mountAll(); return; }
        setTimeout(function () {
            recheckCount++;
            if (Object.keys(slotStore).length > 0) {
                mounted = true;
                renderSlots();
                setTimeout(function () { setStatus('面板已加载 (' + Object.keys(slotStore).length + ' 个插槽)'); }, 200);
                return;
            }
            mountAll();
        }, delays[Math.min(recheckCount, delays.length - 1)]);
    }

    function pickApply(m) {
        if (!m) return null;
        if (typeof m.apply === 'function') return m;
        if (m.default && typeof m.default.apply === 'function') return m.default;
        return null;
    }

    function mountAll() {
        if (mounted) return;
        var applied = 0;
        var errors = [];
        var missing = [];
        var missingSeen = {};
        function recordMissing(name) {
            if (missingSeen[name]) return;
            missingSeen[name] = true;
            missing.push(String(name));
        }
        // 经典 script 直载 client.js 的 module.exports 导出路径
        if (globalModule.exports && !globalModule.mounted) {
            globalModule.mounted = true;
            try {
                var target = pickApply(globalModule.exports);
                if (target) {
                    var ret = target.apply(makeCtx());
                    if (ret && typeof ret.then === 'function') {
                        ret.then(function () { applied++; finishStep(); }, function (e) { errors.push(String(e && e.message ? e.message : e)); applied++; finishStep(); });
                    } else { applied++; }
                } else {
                    errors.push('client.js 未导出 apply 接口');
                }
            } catch (e) {
                errors.push(String(e && e.message ? e.message : e));
            }
        }
        pending.forEach(function (def) {
            try {
                var module = { exports: {} };
                var ctx = makeCtx();
                var result = def.factory(makeRequire(recordMissing), module, module.exports);
                var target = pickApply(module.exports) || pickApply(result);
                if (target) {
                    var ret = target.apply(ctx);
                    if (ret && typeof ret.then === 'function') {
                        // 异步 apply：等待完成后复查插槽表与报错
                        ret.then(function () { applied++; finishStep(); }, function (e) {
                            errors.push(String(e && e.message ? e.message : e)); applied++; finishStep();
                        });
                    } else {
                        applied++;
                    }
                } else {
                    errors.push('未导出 apply 接口（' + def.id + '）');
                }
            } catch (e) {
                errors.push(String(e && e.message ? e.message : e));
            }
        });
        pending = [];
        missing.forEach(function (n) { recordGlobalMissing(n); });
        window.__dshShimMissing__ = missingList.slice();
        function finishStep() {
            var n = Object.keys(slotStore).length;
            if (n > 0) { mounted = true; renderSlots(); setTimeout(function () { setStatus('面板已加载 (' + n + ' 个插槽)'); }, 200); return; }
            if (mounted) return;
            reportResult(applied, errors);
        }
        finishStep();
    }
    window.require = makeRequire(recordGlobalMissing);
    window.module = globalModule;
    window.exports = globalModule.exports;
    return {
        load: function (def) { pending.push(def); },
        _mountAll: mountAll,
        _runtimeFailed: function () {
            var docs = document.getElementById('docs-fallback');
            if (docs) docs.style.display = '';
            setStatus('UI 运行时加载失败（网络受限），已显示文档');
        }
    };
})();
window.__dshPanelMountAll__ = function () {
    var tries = 0;
    var timer = setInterval(function () {
        tries++;
        window.__ModuleLoader__._mountAll();
        if (window.React && window.ReactDOM) {
            clearInterval(timer);
            if (document.readyState !== 'complete') {
                window.addEventListener('load', function () { window.__ModuleLoader__._mountAll(); });
            }
        } else if (tries > 100) {
            clearInterval(timer);
            window.__ModuleLoader__._runtimeFailed();
        }
    }, 100);
};
""".trimIndent()
    internal fun extractDefineTools(root: File): List<ScriptToolDef> {
        val regex = Regex(
            pattern = """defineTool\s*\(\s*\{[\s\S]{0,400}?name\s*:\s*["'`]([\w.\-/]+)["'`][\s\S]{0,800}?description\s*:\s*["'`]([\s\S]{0,300}?)["'`]""",
        )
        return root.walkTopDown()
            .filter { it.isFile && (it.extension == "js" || it.extension == "ts") }
            .sortedBy { it.relativeTo(root).path }
            .flatMap { file ->
                val text = runCatching { file.readText() }.getOrDefault("")
                regex.findAll(text).mapNotNull { match ->
                    val toolName = match.groupValues[1]
                    if (toolName.isBlank()) return@mapNotNull null
                    ScriptToolDef(
                        name = toolName,
                        description = match.groupValues[2].replace(Regex("\\s+"), " ").trim(),
                    )
                }
            }
            .distinctBy { it.name }
            .toList()
    }

    /** 定位解压后的仓库根目录（codeload zip 会保留顶层 <repo>-<ref>/ 目录） */
    private fun locateRepoRoot(tempRoot: File, repo: String): File? {
        val dirs = tempRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (dirs.isEmpty()) return null
        return dirs.firstOrNull { it.isDirectory && it.name.startsWith(repo) }
            ?: dirs.firstOrNull { File(it, "package.json").isFile }
            ?: dirs.first()
    }

    /**
     * 生成插件文档页 HTML（写入 zip 的 web/index.html）。
     * 以仓库 README 为内容源，图片/相对链接解析为 GitHub 绝对地址；
     * 安装后在聊天抽屉侧边栏与详情对话框可见可打开，让 UI/面板类插件"看得见"。
     */
    internal fun buildDocsPage(root: File, ref: DshRepoRef): String {
        val readme = root.walkTopDown()
            .filter { it.isFile && it.name.equals("README.md", ignoreCase = true) }
            .firstOrNull()
            ?.let { runCatching { it.readText() }.getOrNull() }
            .orEmpty()
        val rawBase = "https://raw.githubusercontent.com/${ref.owner}/${ref.repo}/${ref.ref}/"
        val body = if (readme.isBlank()) {
            "<p>该插件未提供 README 文档。</p>"
        } else {
            markdownToHtml(readme, ref)
        }
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("""<html lang="zh"><head><meta charset="utf-8">""")
            appendLine("""<meta name="viewport" content="width=device-width, initial-scale=1">""")
            appendLine("<title>${escapeHtml(ref.repo)}</title>")
            appendLine(DOCS_PAGE_CSS)
            appendLine("</head><body>")
            appendLine(
                """<p class="meta">DSH 插件 · <a href="https://github.com/${escapeHtml(ref.owner)}/${
                    escapeHtml(ref.repo)
                }">GitHub 仓库</a></p>"""
            )
            appendLine(body)
            appendLine("</body></html>")
        }.replace("__RAW_BASE__", rawBase)
    }

    /**
     * 轻量 Markdown → HTML：标题/列表/引用/围栏代码块/行内样式/链接/图片。
     * 相对路径资源以 __RAW_BASE__ 前缀占位（渲染前替换为 GitHub raw 地址）。
     */
    internal fun markdownToHtml(md: String, ref: DshRepoRef): String {
        val out = StringBuilder()
        var inCode = false
        var listOpen = false
        fun closeList() {
            if (listOpen) {
                out.append("</ul>\n")
                listOpen = false
            }
        }
        md.lines().forEach { raw ->
            val line = raw.trimEnd('\n')
            when {
                line.trimStart().startsWith("```") -> {
                    closeList()
                    if (inCode) {
                        out.append("</code></pre>\n")
                    } else {
                        out.append("<pre><code>")
                    }
                    inCode = !inCode
                }
                inCode -> out.append(escapeHtml(line)).append('\n')
                line.isBlank() -> closeList()
                line.startsWith("#") -> {
                    closeList()
                    val level = line.takeWhile { it == '#' }.length.coerceAtMost(6)
                    val text = inline(line.dropWhile { it == '#' }.trim(), ref)
                    out.append("<h$level>$text</h$level>\n")
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    if (!listOpen) {
                        out.append("<ul>\n")
                        listOpen = true
                    }
                    out.append("<li>").append(inline(line.trimStart().drop(2).trim(), ref)).append("</li>\n")
                }
                line.startsWith(">") -> {
                    closeList()
                    out.append("<blockquote>").append(inline(line.removePrefix(">").trim(), ref)).append("</blockquote>\n")
                }
                else -> {
                    closeList()
                    out.append("<p>").append(inline(line, ref)).append("</p>\n")
                }
            }
        }
        if (inCode) out.append("</code></pre>\n")
        closeList()
        return out.toString()
    }

    /** 行内 Markdown：图片、链接、粗体、斜体、行内代码；相对链接指向 GitHub 页面 */
    private fun inline(text: String, ref: DshRepoRef): String {
        var s = escapeHtml(text)
        // 图片：![alt](src)，相对路径走 __RAW_BASE__ 占位
        s = s.replace(Regex("""!\[([^\]]*)]\(([^)\s]+)\)""")) { m ->
            val alt = m.groupValues[1]
            val src = m.groupValues[2]
            val resolved = if (src.startsWith("http")) src else "__RAW_BASE__${src.trimStart('/')}"
            """<img src="${escapeAttr(resolved)}" alt="${escapeAttr(alt)}" loading="lazy">"""
        }
        // 链接：[text](url)，相对路径指向仓库 blob 页面
        s = s.replace(Regex("""\[([^\]]+)]\(([^)\s]+)\)""")) { m ->
            val label = m.groupValues[1]
            val href = m.groupValues[2]
            val resolved = if (href.startsWith("http") || href.startsWith("#")) {
                href
            } else {
                "https://github.com/${ref.owner}/${ref.repo}/blob/${ref.ref}/${href.trimStart('/')}"
            }
            """<a href="${escapeAttr(resolved)}">${label}</a>"""
        }
        s = s.replace(Regex("""\*\*([^*]+)\*\*"""), "<b>$1</b>")
        s = s.replace(Regex("""\*([^*\n]+)\*"""), "<i>$1</i>")
        s = s.replace(Regex("""`([^`]+)`"""), "<code>$1</code>")
        return s
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun escapeAttr(s: String): String = escapeHtml(s).replace("\"", "&quot;")

    private val DOCS_PAGE_CSS = """<style>
        body { font-family: -apple-system, sans-serif; padding: 16px; max-width: 760px;
               margin: 0 auto; color: #1c1b1f; line-height: 1.6; }
        img { max-width: 100%; height: auto; border-radius: 8px; margin: 8px 0; }
        pre { background: #f5f4f8; padding: 12px; border-radius: 8px; overflow-x: auto; }
        code { background: #f5f4f8; padding: 2px 4px; border-radius: 4px; font-size: 0.9em; }
        pre code { background: none; padding: 0; }
        blockquote { border-left: 3px solid #ccc; margin: 8px 0; padding: 2px 12px; color: #555; }
        a { color: #0061a4; }
        .meta { color: #666; font-size: 0.85em; border-bottom: 1px solid #eee; padding-bottom: 8px; }
        @media (prefers-color-scheme: dark) {
            body { background: #141218; color: #e6e0e9; }
            pre, code { background: #211f26; }
            blockquote { border-color: #444; color: #aaa; }
            a { color: #9acbff; }
            .meta { border-color: #333; }
        }
    </style>"""

    internal fun convertToZip(info: PluginInfo, indexHtml: String, clientJs: String?): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry(PluginManager.METADATA_FILE))
            zip.write(PluginJson.toJson(info).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("web/index.html"))
            zip.write(indexHtml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (clientJs != null) {
                zip.putNextEntry(ZipEntry("web/plugin.client.js"))
                zip.write(clientJs.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                // React 运行时随包分发：面板加载零 CDN 依赖（网络受限环境也能真实运行 UI）
                bundledReactAssets().forEach { (name, bytes) ->
                    if (bytes != null) {
                        zip.putNextEntry(ZipEntry("web/$name"))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
        }
        return baos.toByteArray()
    }

    /** App 内置的 React UMD 运行时；assets 缺失时返回 null 项，由页面回退 CDN */
    private fun bundledReactAssets(): List<Pair<String, ByteArray?>> {
        fun read(name: String): ByteArray? =
            context?.assets?.open("dsh/$name")?.use { it.readBytes() }
        return listOf(
            "react.production.min.js" to read("react.production.min.js"),
            "react-dom.production.min.js" to read("react-dom.production.min.js"),
        )
    }

    /**
     * 面板入口扩展点：注册侧边栏 webview 动作（payload 指向包内 web/index.html），
     * 使插件在聊天抽屉侧边栏与详情对话框中可见可打开。
     */
    internal fun docsSidebarEntry(info: PluginInfo): PluginExtensionPoints? =
        info.takeIf { it.repository.isNotBlank() }?.let {
            PluginExtensionPoints(
                sidebarActions = listOf(
                    PluginExtensionAction(
                        id = "${it.id}_panel",
                        label = it.name,
                        target = "webview",
                        payload = "plugin://${it.id}/index.html",
                    )
                )
            )
        }

    private fun download(url: String): ByteArray {
        val client = httpClient.newBuilder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("拉取 DSH 仓库失败: HTTP ${response.code}")
            val body = response.body ?: error("空响应")
            // 防御超大仓库把内存打爆（codeload 是全仓库 zip）
            val declared = body.contentLength()
            if (declared > MAX_REPO_ZIP_BYTES) error("仓库包过大（${declared / 1024 / 1024}MB），超过上限")
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_REPO_ZIP_BYTES) error("仓库包过大，超过下载上限")
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        }
    }

    private val PANEL_PAGE_CSS = """<style>
        #panel-root { min-height: 60vh; }
        #status a { color: #0061a4; }
        .dsh-slot-container { display: flex; flex-direction: column; gap: 16px; padding: 12px; }
        .dsh-slot-section { border: 1px solid var(--dsw-alias-border-l1, #e0e0e0); border-radius: 8px; overflow: hidden; }
        .dsh-slot-title { padding: 8px 12px; font-size: 12px; font-weight: 600; color: #666; background: #f5f5f5; border-bottom: 1px solid var(--dsw-alias-border-l1, #e0e0e0); text-transform: uppercase; letter-spacing: 0.5px; }
        .dsh-slot-content { padding: 8px; }
    </style>"""

    private companion object {
        /** README 兜底转换所需的最小说明长度，过短视为无有效文档 */
        const val MIN_README_LEN = 200

        /** 工作区命令提示中最多列出的 bin 入口数量 */
        const val MAX_BIN_HINTS = 3

        /** client.js 打包体积上限（防止超大 bundle 拖垮 WebView） */
        const val MAX_CLIENT_JS_BYTES = 3 * 1024 * 1024
        const val MAX_REPO_ZIP_BYTES = 64L * 1024 * 1024
    }
}
