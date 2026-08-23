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
            val tempRoot = File.createTempFile("dsh-", "-convert")
                .apply { delete(); mkdirs() }
            try {
                PluginManager.unzipTo(bytes, tempRoot)
                val root = locateRepoRoot(tempRoot, ref.repo)
                    ?: error("仓库内容为空或无法解压")
                convertToZip(convertRepo(root, ref))
            } finally {
                tempRoot.deleteRecursively()
            }
        }
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
        val name = pkgString("displayName", "name") ?: ref.repo
        val description = pkgString("description").orEmpty()
        val npmPackage = (pkg?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("github:") }
        val workspaceCommandHint = buildWorkspaceCommandHint(npmPackage, root)

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
                version = pkgString("version") ?: "1.0.0",
                description = description.ifBlank { "DeepSeek Harness 技能包 $name" },
                author = ref.owner,
                category = "skill",
                repository = "https://github.com/${ref.owner}/${ref.repo}",
                systemPrompt = (prompt + workspaceCommandHint).take(PluginManager.MAX_SYSTEM_PROMPT_LEN),
                type = PluginCategories.TYPE_SKILL,
                tags = listOf("dsh", "skill"),
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
                version = pkgString("version") ?: "1.0.0",
                description = description.ifBlank { "DeepSeek Harness 插件 $name" },
                author = ref.owner,
                category = "general",
                repository = "https://github.com/${ref.owner}/${ref.repo}",
                systemPrompt = (prompt + workspaceCommandHint).take(PluginManager.MAX_SYSTEM_PROMPT_LEN),
                type = PluginCategories.TYPE_PLUGIN,
                tags = listOf("dsh"),
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
                version = pkgString("version") ?: "1.0.0",
                description = description.ifBlank { "DeepSeek Harness CLI 工具 $name" },
                author = ref.owner,
                category = "tools",
                repository = "https://github.com/${ref.owner}/${ref.repo}",
                systemPrompt = prompt,
                type = PluginCategories.TYPE_PLUGIN,
                tags = listOf("dsh", "cli"),
            )
        }

        // 4. 兜底 README 说明型（无 SKILL.md / 工具定义但文档丰富时仍可导入为知识参考）
        val readme = root.walkTopDown()
            .filter { it.isFile && it.name.equals("README.md", ignoreCase = true) }
            .firstOrNull()
            ?.let { runCatching { it.readText().trim() }.getOrNull() }
            .orEmpty()
        if (readme.length >= MIN_README_LEN) {
            return PluginInfo(
                id = "dsh-${ref.slug}",
                name = name,
                version = pkgString("version") ?: "1.0.0",
                description = description.ifBlank { "DeepSeek Harness 插件 $name" },
                author = ref.owner,
                category = "knowledge",
                repository = "https://github.com/${ref.owner}/${ref.repo}",
                systemPrompt = "以下是 DeepSeek Harness 插件「$name」的说明文档：\n\n${readme.take(PluginManager.MAX_SYSTEM_PROMPT_LEN)}",
                type = PluginCategories.TYPE_SKILL,
                tags = listOf("dsh", "docs"),
            )
        }

        error(
            "DSH 插件「$name」无可迁移能力（纯 UI 增强 / Node 宿主运行时依赖），无法转换为 RikkaHub 插件"
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

    /** 静态扫描源码中 defineTool({ name: 'x', description: 'y' }) 声明 */
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

    private fun convertToZip(info: PluginInfo): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry(PluginManager.METADATA_FILE))
            zip.write(PluginJson.toJson(info).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun download(url: String): ByteArray {
        val client = httpClient.newBuilder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("拉取 DSH 仓库失败: HTTP ${response.code}")
            return response.body?.bytes() ?: error("空响应")
        }
    }

    private companion object {
        /** README 兜底转换所需的最小说明长度，过短视为无有效文档 */
        const val MIN_README_LEN = 200

        /** 工作区命令提示中最多列出的 bin 入口数量 */
        const val MAX_BIN_HINTS = 3
    }
}
