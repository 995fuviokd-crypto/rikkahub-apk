package me.rerere.rikkahub.data.ai.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.AgentPlatform
import me.rerere.workspace.WorkspaceManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** 平台 Agent 在指定工作区内的安装/就绪状态 */
enum class AgentEnvStatus {
    /** 尚未检测 */
    UNKNOWN,

    /** 运行时（node/npm）、常用工具与 CLI 均已就绪 */
    READY,

    /** node/npm 缺失，需要先安装运行时 */
    NODE_MISSING,

    /** 运行时已就绪，但常用工具（git/curl/unzip 等）缺失 */
    TOOLS_MISSING,

    /** 运行时已就绪，但 CLI 包尚未安装 */
    CLI_MISSING,

    /** 工作区根文件系统未安装，无法执行任何安装 */
    NO_ROOTFS,
}

/** 安装过程中的进度阶段 */
enum class AgentInstallPhase {
    /** 检测环境 */
    CHECKING,

    /** 安装 node/npm */
    INSTALLING_NODE,

    /** 安装常用工具（git/curl/unzip 等） */
    INSTALLING_TOOLS,

    /** 全局安装 CLI 包 */
    INSTALLING_CLI,

    /** 校验安装结果 */
    VERIFYING,

    /** 已完成 */
    DONE,

    /** 失败 */
    FAILED,
}

/**
 * 安装进度事件。
 * @param percent 0-100 的整体百分比; null 表示该阶段尚无确定进度(由 UI 层平滑推进)
 */
data class AgentInstallProgress(
    val phase: AgentInstallPhase,
    val detail: String = "",
    val percent: Int? = null,
)

/**
 * Ensures the runtime dependencies for a platform agent are installed inside a workspace:
 * Node.js/npm first, then the agent's CLI package (installed globally via `npm install -g`
 * so the binary is reusable and its presence can be probed cheaply).
 *
 * Installation mirrors the DevTool flow used by the workspace detail page: commands run
 * through [WorkspaceManager.executeCommand] inside the PRoot container, retried up to
 * [MAX_ATTEMPTS] times. This class is also the backend for the "Agent 模式管理" settings
 * page, which reports install status and step-by-step progress via [checkStatus] and
 * [installWithProgress].
 */
class AcpEnvironmentManager(
    private val workspaceManager: WorkspaceManager,
    private val context: Context,
    private val logBus: AgentInstallLogBus = AgentInstallLogBus(),
) {
    private val readyRoots = ConcurrentHashMap.newKeySet<String>()

    /**
     * 检测 [platform] 在 [root] 工作区内的安装状态。纯只读，不触发网络下载。
     */
    suspend fun checkStatus(root: String, platform: AgentPlatform): AgentEnvStatus = withContext(Dispatchers.IO) {
        val cacheKey = "$root|${platform.name}"
        if (cacheKey in readyRoots) return@withContext AgentEnvStatus.READY
        if (!workspaceManager.hasRootfs(root)) return@withContext AgentEnvStatus.NO_ROOTFS
        // 合并在单条 shell 命令中进行三项检测, 减少 PRoot 启动开销
        val combinedCommand = buildString {
            append("echo __NODE__ && command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && echo __NODE_OK__ || echo __NODE_NO__;")
            append("echo __TOOLS__ && ")
            append(COMMON_TOOLS.split(" ").joinToString(" && ") { "command -v $it >/dev/null 2>&1" })
            append(" && echo __TOOLS_OK__ || echo __TOOLS_NO__;")
            append("echo __CLI__ && npm ls -g \"${platform.cliPackage}\" --depth=0 >/dev/null 2>&1 && echo __CLI_OK__ || echo __CLI_NO__")
        }
        val result = runCatching {
            workspaceManager.executeCommand(
                root = root,
                command = combinedCommand,
                timeoutMillis = CHECK_TIMEOUT_MS,
            )
        }.getOrNull()
        // 基础设施故障(容器启动失败/超时)与"未安装"语义分开:
        // 误报 NODE_MISSING 会引导用户重跑整条安装链, 表现为连接又慢又反复
        if (result == null || result.timedOut) return@withContext AgentEnvStatus.UNKNOWN
        return@withContext when {
            !result.stdout.contains("__NODE_OK__") -> AgentEnvStatus.NODE_MISSING
            !result.stdout.contains("__TOOLS_OK__") -> AgentEnvStatus.TOOLS_MISSING
            !result.stdout.contains("__CLI_OK__") -> AgentEnvStatus.CLI_MISSING
            else -> AgentEnvStatus.READY
        }
    }

    /**
     * 检测工作区是否具备插件 CLI 运行的最低环境(Node.js/npm + 常用工具)。
     * 与 [checkStatus] 的区别: 不检测任何平台 CLI, 供插件列表的环境提醒使用。
     */
    suspend fun checkRuntime(root: String): AgentEnvStatus = withContext(Dispatchers.IO) {
        if (!workspaceManager.hasRootfs(root)) return@withContext AgentEnvStatus.NO_ROOTFS
        val combinedCommand = buildString {
            append("echo __NODE__ && command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && echo __NODE_OK__ || echo __NODE_NO__;")
            append("echo __TOOLS__ && ")
            append(COMMON_TOOLS.split(" ").joinToString(" && ") { "command -v $it >/dev/null 2>&1" })
            append(" && echo __TOOLS_OK__ || echo __TOOLS_NO__")
        }
        val result = runCatching {
            workspaceManager.executeCommand(
                root = root,
                command = combinedCommand,
                timeoutMillis = CHECK_TIMEOUT_MS,
            )
        }.getOrNull()
        if (result == null || result.timedOut) return@withContext AgentEnvStatus.UNKNOWN
        return@withContext when {
            !result.stdout.contains("__NODE_OK__") -> AgentEnvStatus.NODE_MISSING
            !result.stdout.contains("__TOOLS_OK__") -> AgentEnvStatus.TOOLS_MISSING
            else -> AgentEnvStatus.READY
        }
    }

    /**
     * 一键补全插件运行环境: Node.js/npm 缺失则安装, 常用工具缺失则补装, 不涉及平台 CLI。
     * 进度经 [onProgress] 回报(阶段级里程碑 + node 脚本内 __P__ 标记的细粒度推进)。
     */
    suspend fun ensureRuntimeWithProgress(
        root: String,
        onProgress: (AgentInstallProgress) -> Unit = {},
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            if (!workspaceManager.hasRootfs(root)) {
                throw IllegalStateException("工作区根文件系统未安装，请先在对应工作区安装系统环境")
            }

            if (!hasNode(root)) {
                val detail = "正在安装 Node.js 与 npm（内置离线包）…"
                onProgress(AgentInstallProgress(AgentInstallPhase.INSTALLING_NODE, detail, 5))
                // 优先使用 APK 内置的离线 Node 运行时, 避免联网下载失败; 离线解压失败再回退网络安装
                val offlineOk = installNodeOffline(root, onProgress)
                if (!offlineOk) {
                    val detailNet = "正在联网安装 Node.js 与 npm（国内镜像加速）…"
                    onProgress(AgentInstallProgress(AgentInstallPhase.INSTALLING_NODE, detailNet, 5))
                    var stage = -1
                    val result = runWithRetry("node/npm") {
                        workspaceManager.executeCommand(
                            root = root,
                            command = NODE_INSTALL_SCRIPT,
                            timeoutMillis = INSTALL_TIMEOUT_MS,
                            onOutput = { chunk ->
                                Regex("__P__(\\d+)").findAll(chunk).forEach { match ->
                                    val marker = match.groupValues[1].toIntOrNull() ?: return@forEach
                                    if (marker > stage) {
                                        stage = marker
                                        onProgress(
                                            AgentInstallProgress(AgentInstallPhase.INSTALLING_NODE, detailNet, 5 + marker * 40 / 100)
                                        )
                                    }
                                }
                            },
                        )
                    }
                    result.getOrElse { error("Node.js 安装失败：${it.message}，请重试") }
                        .takeIf { it.exitCode == 0 && it.stdout.contains("__OK__") }
                        ?: error("Node.js 安装失败，请检查网络后重试")
                }
                check(hasNode(root)) { "Node.js 安装后校验失败" }
                onProgress(AgentInstallProgress(AgentInstallPhase.INSTALLING_NODE, detail, 50))
            }

            if (!hasCoreTools(root)) {
                val detail = "正在安装常用工具（git/curl/unzip 等）…"
                onProgress(AgentInstallProgress(AgentInstallPhase.INSTALLING_TOOLS, detail, 55))
                val result = runWithRetry("tools") {
                    workspaceManager.executeCommand(
                        root = root,
                        command = TOOLS_INSTALL_SCRIPT,
                        timeoutMillis = INSTALL_TIMEOUT_MS,
                    )
                }
                result.getOrElse { error("工具安装失败：${it.message}") }
                    .takeIf { it.exitCode == 0 && it.stdout.contains("__OK__") }
                    ?: error("工具安装失败，请检查网络或包管理器")
                check(hasCoreTools(root)) { "工具安装后校验失败" }
            }

            onProgress(AgentInstallProgress(AgentInstallPhase.DONE, "环境已就绪", 100))
        }
    }.onFailure { error ->
        Log.w(TAG, "runtime install failed in $root", error)
        onProgress(
            AgentInstallProgress(
                AgentInstallPhase.FAILED,
                error.message?.take(ERROR_DETAIL_MAX_CHARS) ?: "环境安装失败",
            ),
        )
    }

    /**
     * 优先使用 APK 内置的离线 Node.js 运行时, 从 assets 解压进 rootfs 的 /opt/nodejs,
     * 并配置 npm 国内镜像与 PATH。完全离线, 不依赖网络。返回是否成功。
     */
    private suspend fun installNodeOffline(
        root: String,
        onProgress: (AgentInstallProgress) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val arch = detectArch()
            if (arch == null) {
                Log.i(TAG, "offline node: unsupported arch, fallback to network")
                return@runCatching false
            }
            val assetPath = "offline/node/$arch/node.tar.gz"
            val input = runCatching { context.assets.open(assetPath) }.getOrNull()
            if (input == null) {
                Log.i(TAG, "offline node: asset missing $assetPath, fallback to network")
                return@runCatching false
            }
            onProgress(AgentInstallProgress(AgentInstallPhase.INSTALLING_NODE, "正在解压内置 Node.js 运行时…", 12))
            val nodeDir = File(workspaceManager.linuxDir(root), "opt/nodejs")
            nodeDir.mkdirs()
            input.use { stream ->
                extractTarGz(BufferedInputStream(stream), nodeDir)
            }
            // 建立 node/npm/npx 到 /usr/local/bin 的软链
            val usrLocalBin = File(workspaceManager.linuxDir(root), "usr/local/bin")
            usrLocalBin.mkdirs()
            listOf("node", "npm", "npx").forEach { name ->
                val link = File(usrLocalBin, name)
                if (!link.exists()) {
                    val target = "/opt/nodejs/bin/$name"
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            link.toPath(),
                            java.nio.file.Paths.get(target),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "create symlink failed for $name", e)
                        // 回退: 写一个 wrapper 脚本, 兼容不支持软链的文件系统
                        link.writeText("#!/bin/sh\nexec /opt/nodejs/bin/$name \"\$@\"\n")
                        link.setExecutable(true)
                    }
                }
            }
            // 预创建 npm 缓存目录, 避免 PRoot 下 rename ENOENT
            val npmCache = File(workspaceManager.linuxDir(root), "root/.npm/_cacache/content-v2/sha512")
            npmCache.mkdirs()
            File(workspaceManager.linuxDir(root), "root/.npm/_cacache/tmp").mkdirs()
            // 配置 npm 全局 prefix 与国内镜像
            runCommandOk(
                root,
                "npm config -g set prefix /usr/local 2>/dev/null; " +
                    "npm config -g set registry https://registry.npmmirror.com 2>/dev/null; " +
                    "npm config -g set fund false 2>/dev/null; " +
                    "npm config -g set audit false 2>/dev/null; echo done",
            )
            // PATH 持久化
            val profileDir = File(workspaceManager.linuxDir(root), "etc/profile.d")
            profileDir.mkdirs()
            File(profileDir, "rikkahub-path.sh").writeText("export PATH=\"/opt/nodejs/bin:/usr/local/bin:\$PATH\"\n")
            onProgress(AgentInstallProgress(AgentInstallPhase.INSTALLING_NODE, "内置 Node.js 已就绪", 45))
            true
        }.getOrElse { e ->
            Log.w(TAG, "offline node install failed", e)
            false
        }
    }

    /** 检测容器架构, 返回 assets 子目录名(arm64/x64); 未知返回 null */
    private fun detectArch(): String? {
        val arch = runCatching { System.getProperty("os.arch")?.lowercase() }.getOrNull()
        return when {
            arch != null && (arch.contains("aarch64") || arch.contains("arm64")) -> "arm64"
            arch != null && (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) -> "x64"
            else -> null
        }
    }

    /**
     * 轻量自包含的 tar.gz 解压器(Android 无内置 tar 读取), 处理普通文件/目录/软链,
     * 以及 GNU 长文件名('L') 头, 并保留可执行位。tar 头为 512 字节, 数据按 512 对齐。
     */
    private fun extractTarGz(input: java.io.InputStream, destDir: File) {
        GZIPInputStream(input).use { gz ->
            val tar = BufferedInputStream(gz)
            val header = ByteArray(512)
            var pendingLongName: String? = null
            while (true) {
                val read = readFully(tar, header)
                if (read == -1 || read == 0) break
                if (header.all { it == 0.toByte() }) break
                val type = header[156].toInt().toChar()
                val size = parseOctal(header, 124, 12) ?: 0
                // GNU 长文件名头: 数据段为完整路径, 供紧随其后的条目使用
                if (type == 'L') {
                    pendingLongName = readDataString(tar, size)
                    continue
                }
                var name = parseTarString(header, 0, 100)
                pendingLongName?.let { long ->
                    name = long
                    pendingLongName = null
                }
                val mode = parseOctal(header, 100, 8) ?: 0
                if (name.isEmpty()) {
                    skipAligned(tar, size)
                    continue
                }
                // 去掉开头的 ./ 或 /
                val cleanName = name.removePrefix("./")
                val prefix = parseTarString(header, 345, 155)
                val rawName = if (prefix.isNotEmpty() && !cleanName.startsWith(prefix)) "$prefix/$cleanName" else cleanName
                // 剥掉顶层目录(node tarball 顶层为 node-v22.14.0-linux-<arch>, 等效 --strip-components=1)
                val stripped = rawName.split('/').drop(1).joinToString("/")
                if (stripped.isEmpty()) {
                    skipAligned(tar, size)
                    continue
                }
                val fullTarget = File(destDir, stripped).normalize()
                val parent = fullTarget.parentFile
                if (parent != null) parent.mkdirs()
                when (type) {
                    '5' -> fullTarget.mkdirs()
                    '2' -> {
                        val linkTarget = parseTarString(header, 157, 100)
                        try {
                            java.nio.file.Files.createSymbolicLink(
                                fullTarget.toPath(),
                                java.nio.file.Paths.get(linkTarget),
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "skip symlink $stripped -> $linkTarget", e)
                        }
                    }
                    '0', '\u0000' -> {
                        FileOutputStream(fullTarget).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var remaining = size
                            while (remaining > 0) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val n = tar.read(buf, 0, toRead)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        if (mode and 0x40L != 0L) fullTarget.setExecutable(true, false)
                        skipPad(tar, size)
                    }
                    else -> skipAligned(tar, size)
                }
            }
        }
    }

    /** 读取一个条目数据段(整块)并作为 UTF-8 字符串返回, 同时跳过填充对齐 */
    private fun readDataString(tar: java.io.InputStream, size: Long): String {
        val buf = ByteArray(size.toInt().coerceAtLeast(0))
        var off = 0
        while (off < buf.size) {
            val n = tar.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) skipFully(tar, pad)
        return String(buf, 0, off, Charsets.UTF_8).trimEnd('\u0000')
    }

    /** 从 tar 头读取一个 NUL 结尾的 ASCII 字符串字段 */
    private fun parseTarString(h: ByteArray, offset: Int, len: Int): String {
        val end = (offset until offset + len).firstOrNull { h[it] == 0.toByte() } ?: (offset + len)
        return String(h, offset, end - offset, Charsets.US_ASCII)
    }

    /** 解析 tar 头的八进制数字字段(以空格或 NUL 结尾) */
    private fun parseOctal(h: ByteArray, offset: Int, len: Int): Long? {
        var v = 0L
        var started = false
        for (i in offset until offset + len) {
            val c = h[i].toInt().toChar()
            if (c == '\u0000' || c == ' ') {
                if (started) break else continue
            }
            if (c !in '0'..'7') return null
            started = true
            v = v * 8 + (c - '0')
        }
        return if (started) v else null
    }

    /** 读取完整字节, 返回实际读取数; EOF 返回 -1 */
    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        var n: Int
        while (total < buf.size) {
            n = input.read(buf, total, buf.size - total)
            if (n == -1) break
            total += n
        }
        return if (total == 0) -1 else total
    }

    /** 跳过 [size] 字节并按 512 对齐 */
    private fun skipAligned(input: java.io.InputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) skipFully(input, pad)
    }

    /** 数据已消费后, 仅跳过 [size] 字节对应的 512 对齐填充 */
    private fun skipPad(input: java.io.InputStream, size: Long) {
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) skipFully(input, pad)
    }

    /** 可靠地跳过恰好 [n] 字节(InputStream.skip 可能少跳, 此处循环补足) */
    private fun skipFully(input: java.io.InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            remaining -= read
        }
    }

    /**
     * 将插件的 npm CLI 包全局安装到工作区(prefix=/usr/local), 装完后 npx 直接命中本地包,
     * 免去每次联网解析下载。包名白名单校验防止 shell 注入。
     */
    suspend fun installGlobalPackage(root: String, pkg: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            require(NPM_PACKAGE_REGEX.matches(pkg)) { "非法的 npm 包名：$pkg" }
            check(hasNode(root)) { "Node.js 运行环境未安装，请先完成环境补全" }
            val baseFlags = "--no-audit --no-fund --prefer-offline --fetch-retries=2 --fetch-timeout=60000"
            val cliCommand = buildString {
                append("npm install -g \"$pkg\" --registry=$NPM_MIRROR_REGISTRY $baseFlags 2>&1 && echo __OK__ || ")
                append("(npm install -g \"$pkg\" --registry=$NPM_ALIYUN_REGISTRY $baseFlags 2>&1 && echo __OK__) || ")
                append("(npm install -g \"$pkg\" $baseFlags 2>&1 && echo __OK__)")
            }
            val result = runWithRetry("plugin-pkg:$pkg") {
                workspaceManager.executeCommand(
                    root = root,
                    command = cliCommand,
                    timeoutMillis = CLI_INSTALL_TIMEOUT_MS,
                )
            }
            result.getOrElse { error("安装失败：${it.message}") }
                .takeIf { it.exitCode == 0 && it.stdout.contains("__OK__") }
                ?: error("安装失败：$pkg（官方源与国内镜像均失败）\n${result.getOrNull()?.combinedTail().orEmpty()}")
            check(isGlobalPackageInstalled(root, pkg)) { "安装校验失败：$pkg 未出现在全局 node_modules" }
        }
    }.onFailure { error ->
        Log.w(TAG, "global package install failed for $pkg in $root", error)
    }

    /** 检查 npm 全局包是否已安装(动态 prefix) */
    suspend fun isGlobalPackageInstalled(root: String, pkg: String): Boolean =
        NPM_PACKAGE_REGEX.matches(pkg) && hasCliByPackage(root, pkg)

    private fun hasCliByPackage(root: String, pkg: String): Boolean = runCommandOk(
        root,
        "npm ls -g \"$pkg\" --depth=0 >/dev/null 2>&1 && echo __OK__ || echo __NO__",
    )

    /**
     * 在 [root] 工作区内安装运行 [platform] 所需的全部依赖，并在每个阶段
     * 通过 [onProgress] 回报进度(含整体百分比 0-100)。安装完成/已缓存时返回成功。
     *
     * 百分比构成: 真实里程碑(脚本 __P__ 标记/阶段完成)为主, CLI 下载阶段
     * 因 npm 无可靠进度输出, 以缓慢推进的估算值补间(封顶 92%, 不回退)。
     */
    suspend fun installWithProgress(
        root: String,
        platform: AgentPlatform,
        onProgress: (AgentInstallProgress) -> Unit = {},
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val cacheKey = "$root|${platform.name}"
            if (cacheKey in readyRoots) return@withContext Unit

            logBus.begin(root, platform)
            var nodeStage = -1 // node 脚本内已见的最大 __P__ 标记, 防止跨块/重放导致回退

            fun report(phase: AgentInstallPhase, detail: String, percent: Int?) =
                onProgress(AgentInstallProgress(phase, detail, percent))

            try {
                report(AgentInstallPhase.CHECKING, "正在检测工作区环境…", 2)
                if (!workspaceManager.hasRootfs(root)) {
                    throw IllegalStateException("工作区根文件系统未安装，请先在对应工作区安装系统环境")
                }

                if (!hasNode(root)) {
                    val nodeDetail = "正在安装 Node.js 与 npm（已启用国内镜像加速）…"
                    report(AgentInstallPhase.INSTALLING_NODE, nodeDetail, 5)
                    val nodeResult = runWithRetry("node/npm") {
                        workspaceManager.executeCommand(
                            root = root,
                            command = NODE_INSTALL_SCRIPT,
                            timeoutMillis = INSTALL_TIMEOUT_MS,
                            onOutput = { chunk ->
                                logBus.append(platform, chunk)
                                Regex("__P__(\\d+)").findAll(chunk).forEach { match ->
                                    val marker = match.groupValues[1].toIntOrNull() ?: return@forEach
                                    if (marker > nodeStage) {
                                        nodeStage = marker
                                        report(
                                            AgentInstallPhase.INSTALLING_NODE,
                                            nodeDetail,
                                            5 + marker * 45 / 100,
                                        )
                                    }
                                }
                            },
                        )
                    }
                    nodeResult.getOrElse { error("node/npm 安装失败：${it.message}，请重试") }
                        .takeIf { it.exitCode == 0 && it.stdout.contains("__OK__") }
                        ?: error("node/npm 安装失败${nodeResult.exceptionOrNull()?.let { e -> "（${e.message}）" } ?: ""}，请重试")
                    check(hasNode(root)) { "node/npm 安装后校验失败" }
                    report(AgentInstallPhase.INSTALLING_NODE, nodeDetail, 50)
                } else {
                    logBus.append(platform, "Node.js 已就绪，跳过运行时安装\n")
                }

                if (!hasCoreTools(root)) {
                    val toolsDetail = "正在安装常用工具（git/curl/unzip 等）…"
                    report(AgentInstallPhase.INSTALLING_TOOLS, toolsDetail, 52)
                    val toolsResult = runWithRetry("tools") {
                        workspaceManager.executeCommand(
                            root = root,
                            command = TOOLS_INSTALL_SCRIPT,
                            timeoutMillis = INSTALL_TIMEOUT_MS,
                            onOutput = { chunk -> logBus.append(platform, chunk) },
                        )
                    }
                    toolsResult.getOrElse { error("工具安装失败：${it.message}") }
                        .takeIf { it.exitCode == 0 && it.stdout.contains("__OK__") }
                        ?: error("工具安装失败${toolsResult.exceptionOrNull()?.let { e -> "（${e.message}）" } ?: ""}，请检查网络或包管理器")
                    check(hasCoreTools(root)) { "工具安装后校验失败" }
                    report(AgentInstallPhase.INSTALLING_TOOLS, toolsDetail, 55)
                } else {
                    logBus.append(platform, "常用工具已就绪，跳过\n")
                }

                if (!hasCli(root, platform)) {
                    val cliDetail = "正在下载并安装 ${platform.cliPackage}（国内镜像加速中）…"
                    report(AgentInstallPhase.INSTALLING_CLI, cliDetail, 55)
                    // 修复 PRoot 环境下 npm 缓存目录缺失导致的 ENOENT rename 错误
                    runCatching {
                        workspaceManager.executeCommand(
                            root = root,
                            command = "mkdir -p /root/.npm/_cacache/content-v2/sha512 /root/.npm/_cacache/tmp && npm cache clean --force 2>/dev/null; true",
                            timeoutMillis = CHECK_TIMEOUT_MS,
                        )
                    }
                    // 三级 Registry 降级链：npmmirror(国内最快) → 阿里云镜像(备用) → 官方源(兜底)
                    // --no-audit --no-fund 省去安全审计与赞助信息请求;
                    // --prefer-offline 优先使用已缓存包(镜像源重复安装大幅提速)
                    // 保留默认输出级别让 npm 的下载/安装过程在终端页可见
                    val cliCommand = buildString {
                        val baseFlags = "--no-audit --no-fund --prefer-offline --fetch-retries=2 --fetch-timeout=60000"
                        append("npm install -g \"${platform.cliPackage}\" --registry=$NPM_MIRROR_REGISTRY $baseFlags 2>&1 && echo __OK__ || ")
                        append("(npm install -g \"${platform.cliPackage}\" --registry=$NPM_ALIYUN_REGISTRY $baseFlags 2>&1 && echo __OK__) || ")
                        append("(npm install -g \"${platform.cliPackage}\" $baseFlags 2>&1 && echo __OK__)")
                    }
                    logBus.append(platform, "\$ $cliCommand\n")
                    // npm 无标准进度输出, 用缓慢逼近的估算值补间(封顶 92%), 完成后由真实结果接管
                    coroutineScope {
                        val ticker = launch {
                            var estimate = 56
                            while (estimate < 92) {
                                delay(PROGRESS_TICK_MS)
                                estimate += maxOf(1, ((92 - estimate) * 0.04).toInt())
                                report(AgentInstallPhase.INSTALLING_CLI, cliDetail, estimate)
                            }
                        }
                        try {
                            val cliResult = runWithRetry(platform.cliPackage) {
                                workspaceManager.executeCommand(
                                    root = root,
                                    command = cliCommand,
                                    timeoutMillis = CLI_INSTALL_TIMEOUT_MS,
                                    onOutput = { chunk -> logBus.append(platform, chunk) },
                                )
                            }
                            cliResult.getOrElse { error("CLI 安装失败：${it.message}") }
                                .takeIf { it.exitCode == 0 && it.stdout.contains("__OK__") }
                                ?: error(
                                    "CLI 安装失败：${platform.cliPackage}（官方源与国内镜像均失败）\n" +
                                        cliResult.getOrNull()?.combinedTail().orEmpty().ifBlank { "请检查网络后重试" },
                                )
                        } finally {
                            ticker.cancel()
                        }
                    }
                    check(hasCli(root, platform)) { "CLI 安装后校验失败" }
                    report(AgentInstallPhase.INSTALLING_CLI, cliDetail, 95)
                } else {
                    logBus.append(platform, "${platform.cliPackage} 已存在，跳过安装\n")
                }

                report(AgentInstallPhase.VERIFYING, "正在校验安装结果…", 97)
                ensureCliInstalled(root, platform)

                readyRoots += cacheKey
                logBus.append(platform, "安装完成\n")
                report(AgentInstallPhase.DONE, "安装完成", 100)
                Log.i(TAG, "environment ready for ${platform.name} in $root")
                Unit
            } finally {
                logBus.finish(platform)
            }
        }
    }.onFailure { error ->
        Log.w(TAG, "install failed for ${platform.name} in $root", error)
        onProgress(
            AgentInstallProgress(
                AgentInstallPhase.FAILED,
                error.message?.take(ERROR_DETAIL_MAX_CHARS) ?: "安装失败",
            ),
        )
    }

    /**
     * 从本地离线包(.tgz, `npm pack` 或官网下载的 npm tarball)导入安装 CLI 到全局环境。
     * 包内容先写入工作区文件区, 再由容器内 `npm install -g` 完成 bin 链接与依赖处理,
     * 因此导入后的平台会被 hasCli 正常识别为 READY, 与在线安装行为完全一致。
     */
    suspend fun importPackageArchive(
        root: String,
        archiveName: String,
        bytes: ByteArray,
        onLog: (String) -> Unit = {},
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            require(archiveName.lowercase().endsWith(".tgz") || archiveName.lowercase().endsWith(".tar.gz")) {
                "仅支持 .tgz / .tar.gz 格式的 npm 离线包"
            }
            check(hasNode(root)) { "Node.js 运行环境未安装，请先完成运行时安装再导入" }

            val containerPath = "$IMPORT_DIR_IN_CONTAINER/$archiveName"
            workspaceManager.writeRootfsBytes(root, containerPath, bytes)

            val command = "npm install -g \"$containerPath\" --no-audit --no-fund " +
                "--fetch-retries=2 --fetch-timeout=60000 2>&1 && echo __OK__"
            onLog("\$ $command\n")
            val result = runWithRetry("import:$archiveName") {
                workspaceManager.executeCommand(
                    root = root,
                    command = command,
                    timeoutMillis = CLI_INSTALL_TIMEOUT_MS,
                    onOutput = { chunk -> onLog(chunk) },
                )
            }
            result.getOrElse { error("导入失败：${it.message}") }
                .takeIf { it.exitCode == 0 && it.stdout.contains("__OK__") }
                ?: error("导入失败：npm install 返回异常\n${result.getOrNull()?.combinedTail().orEmpty()}")
            // 清理临时包文件, 保持文件区整洁
            runCatching {
                workspaceManager.executeCommand(
                    root = root,
                    command = "rm -f \"$containerPath\"",
                    timeoutMillis = CHECK_TIMEOUT_MS,
                )
            }
            // 导入完成后失效缓存, 让状态检测重新识别新装的 CLI
            invalidate(root)
            onLog("导入完成\n")
            Unit
        }
    }.onFailure { error ->
        Log.w(TAG, "import failed in $root", error)
        onLog("导入失败：${error.message}\n")
    }

    /**
     * 检测并安装 [platform] 所需环境。兼容原有调用方（进度回调为空）。
     */
    suspend fun ensureReady(root: String, platform: AgentPlatform): Result<Unit> =
        installWithProgress(root, platform)

    /** Returns the argv used to start the agent CLI inside the container. */
    fun cliCommand(platform: AgentPlatform, args: List<String>): List<String> {
        // 经 login bash 启动: 加载 /etc/profile -> profile.d/rikkahub.sh,
        // 使 /opt/nodejs/bin(全局包装)与 /config 扩展点 env 全部生效;
        // 否则 env -i 的裸 PATH 下 npx 找不到已全局安装的包, 会联网重新下载
        val cli = buildString {
            append("exec npx -y ")
            append(shQuote(platform.cliPackage))
            args.forEach { arg ->
                append(' ')
                append(shQuote(arg))
            }
        }
        return listOf("bash", "-lc", cli)
    }

    /** POSIX 单引号包裹, 防止参数中的空格/特殊字符破坏命令边界 */
    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun hasNode(root: String): Boolean = runCommandOk(
        root,
        "command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && echo __OK__",
    )

    private fun hasCoreTools(root: String): Boolean = runCommandOk(
        root,
        COMMON_TOOLS.split(" ").joinToString(" && ") { tool ->
            "command -v $tool >/dev/null 2>&1"
        } + " && echo __OK__",
    )

    private fun hasCli(root: String, platform: AgentPlatform): Boolean {
        // npm ls 对已全局安装的包返回 0，未安装返回非 0
        return runCommandOk(
            root,
            "npm ls -g ${platform.cliPackage} --depth=0 >/dev/null 2>&1 && echo __OK__ || echo __NO__",
        )
    }

    /**
     * 校验 CLI 已正确落入全局 node_modules。纯文件存在性检查:
     * 此前用 `npx --no-install <pkg> --version` 试运行, 大体积包在 PRoot 内首次
     * 启动动辄超过校验超时, 造成"安装成功却报 CLI 无法启动"的误判。
     * 全局路径经 `npm root -g` 动态获取: Node 可能来自 tarball(prefix=/opt/nodejs)
     * 或系统包管理器(prefix=/usr 或 /usr/local), 硬编码路径会在 tarball 场景必败,
     * 导致每次连接都重跑完整安装链。
     */
    private fun ensureCliInstalled(root: String, platform: AgentPlatform) {
        val result = workspaceManager.executeCommand(
            root = root,
            command = "test -d \"\$(npm root -g)/${platform.cliPackage}\" && echo __OK__ || echo __NO__",
            timeoutMillis = CHECK_TIMEOUT_MS,
        )
        check(result.stdout.contains("__OK__")) { "CLI 校验失败：${platform.cliPackage} 未出现在全局 node_modules" }
    }

    private fun runCommandOk(root: String, command: String): Boolean {
        val result = runCatching {
            workspaceManager.executeCommand(
                root = root,
                command = command,
                timeoutMillis = CHECK_TIMEOUT_MS,
            )
        }.getOrNull() ?: return false
        return result.exitCode == 0 && result.stdout.contains("__OK__")
    }

    private suspend fun runWithRetry(
        label: String,
        block: () -> me.rerere.workspace.WorkspaceCommandResult,
    ): Result<me.rerere.workspace.WorkspaceCommandResult> {
        var lastError: String? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = runCatching(block)
            val ok = result.getOrNull()?.let { it.exitCode == 0 && it.stdout.contains("__OK__") } == true
            if (ok) return result
            lastError = when {
                result.exceptionOrNull() != null -> result.exceptionOrNull()!!.message ?: "命令执行异常"
                result.getOrNull()!!.timedOut -> "命令超时"
                else -> result.getOrNull()!!.combinedTail().ifBlank { "未知错误" }
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                Log.w(TAG, "install $label attempt ${attempt + 1} failed: $lastError, retrying…")
                delay(RETRY_DELAY_MS)
            }
        }
        return Result.failure(IllegalStateException(lastError ?: "安装失败"))
    }

    /** Forgets cached readiness, e.g. when the workspace rootfs is reinstalled. */
    fun invalidate(root: String) {
        readyRoots.removeAll { it.startsWith("$root|") }
    }

    companion object {
        private const val TAG = "AcpEnvironmentManager"
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 3_000L
        private const val CHECK_TIMEOUT_MS = 60_000L
        private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L

        /** CLI 包体积普遍较大(claude-code 等 100MB 级), 单次安装给足 20 分钟 */
        private const val CLI_INSTALL_TIMEOUT_MS = 20 * 60 * 1000L

        /** 失败详情展示的最大字符数 */
        private const val ERROR_DETAIL_MAX_CHARS = 300

        /** CLI 安装阶段估算进度的推进间隔 */
        private const val PROGRESS_TICK_MS = 500L

        /** npmmirror（原淘宝）npm 镜像，官方 registry 不可达时的降级源 */
        private const val NPM_MIRROR_REGISTRY = "https://registry.npmmirror.com"

        /** 阿里云 npm 镜像，npmmirror 不可达时的次级降级源 */
        private const val NPM_ALIYUN_REGISTRY = "https://mirrors.aliyun.com/npm/"

        /** 离线导入包在工作区文件区的暂存目录(容器内挂载为 /workspace) */
        private const val IMPORT_DIR_IN_CONTAINER = "/workspace/.agent-import"

        /** 常用工具列表（空格分隔的命令名），用于 hasCoreTools 检测与 TOOLS_INSTALL_SCRIPT 安装 */
        private val COMMON_TOOLS = "git curl wget unzip tar gzip ca-certificates"

        /** npm 包名白名单(scope 包与普通包)，用于插件预装的 shell 注入防护 */
        private val NPM_PACKAGE_REGEX = Regex("@[a-zA-Z0-9][a-zA-Z0-9._-]*/[a-zA-Z0-9][a-zA-Z0-9._-]*|[a-zA-Z0-9][a-zA-Z0-9._-]*")

        /**
         * 常用工具安装脚本：通过系统包管理器安装 git/curl/wget/unzip，静默容错。
         * 与 NODE_INSTALL_SCRIPT 内工具安装块保持同步（双保险：Node 安装时已装过则本脚本跳过）。
         * 支持 apt/apk/yum 三种包管理器。
         */
        private val TOOLS_INSTALL_SCRIPT = """
            export DEBIAN_FRONTEND=noninteractive
            TOOLS="git curl wget unzip tar gzip ca-certificates"
            if command -v apt-get >/dev/null 2>&1; then
              apt-get update -qq >/dev/null 2>&1 || true
              apt-get install -y -qq ${'$'}TOOLS >/dev/null 2>&1 || true
            elif command -v apk >/dev/null 2>&1; then
              apk add --no-cache -q ${'$'}TOOLS >/dev/null 2>&1 || true
            elif command -v yum >/dev/null 2>&1; then
              yum install -y -q ${'$'}TOOLS >/dev/null 2>&1 || true
            fi
            # 可扩展性: /config/tools.txt 声明额外系统包(每行一个, # 注释),
            # 跨设备共享同一份扩展清单
            if [ -f /config/tools.txt ]; then
              EXTRA=${'$'}(grep -vE '^\s*(#|${'$'})' /config/tools.txt | tr '\n' ' ')
              if [ -n "${'$'}EXTRA" ]; then
                if command -v apt-get >/dev/null 2>&1; then
                  apt-get install -y -qq ${'$'}EXTRA >/dev/null 2>&1 || true
                elif command -v apk >/dev/null 2>&1; then
                  apk add --no-cache -q ${'$'}EXTRA >/dev/null 2>&1 || true
                elif command -v yum >/dev/null 2>&1; then
                  yum install -y -q ${'$'}EXTRA >/dev/null 2>&1 || true
                fi
              fi
            fi
            # 无论是否安装成功都报告结束（单包缺失不影响整体）
            echo __OK__
        """.trimIndent()

        /**
         * Node.js 安装脚本(提速版):
         * 1. 优先从 npmmirror 二进制镜像直装官方 Node LTS(tar.gz, 数十秒级), 绕过缓慢的 apt 源;
         * 2. 无下载器时先用 apt 补装 curl(单包, 远快于 update+install nodejs+npm);
         * 3. 镜像/二进制均失败时回退原 apt/apk/yum 包管理器路径。
         * 通过 echo __P__NN 输出里程碑百分比, 由调用方解析后回报真实进度。
         */
        private val NODE_INSTALL_SCRIPT = """
            export DEBIAN_FRONTEND=noninteractive
            ARCH=${'$'}(uname -m 2>/dev/null)
            case "${'$'}ARCH" in
              aarch64|arm64) NARCH=arm64 ;;
              armv7l|armv8l) NARCH=armv7l ;;
              x86_64) NARCH=x64 ;;
              *) NARCH="" ;;
            esac
            NODE_VERSION=v22.14.0
            MIRROR="https://npmmirror.com/mirrors/node"
            if [ -n "${'$'}NARCH" ]; then
              if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
                (apt-get update -qq >/dev/null 2>&1 || true)
                apt-get install -y -qq curl >/dev/null 2>&1 || true
              fi
              if command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1; then
                echo __P__10
                TARBALL="/tmp/node-${'$'}NODE_VERSION-linux-${'$'}NARCH.tar.gz"
                URL="${'$'}MIRROR/${'$'}NODE_VERSION/node-${'$'}NODE_VERSION-linux-${'$'}NARCH.tar.gz"
                if command -v curl >/dev/null 2>&1; then
                  curl -fsSL --connect-timeout 15 --max-time 600 -o "${'$'}TARBALL" "${'$'}URL" || true
                else
                  wget -q -T 15 --tries=1 -O "${'$'}TARBALL" "${'$'}URL" || true
                fi
                if [ -s "${'$'}TARBALL" ] && mkdir -p /opt/nodejs && tar -xzf "${'$'}TARBALL" -C /opt/nodejs --strip-components=1 2>/dev/null; then
                  rm -f "${'$'}TARBALL"
                  ln -sf /opt/nodejs/bin/node /usr/local/bin/node
                  ln -sf /opt/nodejs/bin/npm /usr/local/bin/npm
                  ln -sf /opt/nodejs/bin/npx /usr/local/bin/npx
                  # 全局包装统一落到 /usr/local(prefix 默认随 tarball 为 /opt/nodejs,
                  # 其 bin 不在裸 PATH 中, npx 会误判未安装而重新下载 CLI)
                  npm config -g set prefix /usr/local 2>/dev/null || true
                  # 预创建 npm 缓存目录结构, 防止后续安装时 PRoot 下 rename ENOENT
                  mkdir -p /root/.npm/_cacache/content-v2/sha512 /root/.npm/_cacache/tmp
                  # 配置 npm 全局默认值, 确保子进程(npm postinstall 等)也使用国内镜像
                  npm config -g set registry https://registry.npmmirror.com 2>/dev/null || true
                  npm config -g set fund false 2>/dev/null || true
                  npm config -g set audit false 2>/dev/null || true
                  # 确保 node 系 bin 在 PATH 中持久可用(login 与非 login shell 均覆盖)
                  mkdir -p /etc/profile.d 2>/dev/null
                  echo 'export PATH="/opt/nodejs/bin:/usr/local/bin:${'$'}PATH"' > /etc/profile.d/rikkahub-path.sh
                  echo __P__45
                  # 预装常用工具（git/curl/unzip 等），静默容错
                  echo __P__48
                  command -v apt-get >/dev/null 2>&1 && apt-get install -y -qq git curl wget ca-certificates unzip tar gzip >/dev/null 2>&1 || true
                  echo __P__50
                  node --version >/dev/null 2>&1 && npm --version >/dev/null 2>&1 && { echo __OK__; exit 0; }
                fi
                rm -f "${'$'}TARBALL"
              fi
            fi
            echo __P__30
            # 兜底: 系统包管理器安装(较慢)
            if command -v apt-get >/dev/null 2>&1; then
              (apt-get update -qq >/dev/null 2>&1 || true)
              apt-get install -y -qq nodejs npm >/dev/null 2>&1 && echo __OK__
            elif command -v apk >/dev/null 2>&1; then
              apk add --no-cache -q nodejs npm >/dev/null 2>&1 && echo __OK__
            elif command -v yum >/dev/null 2>&1; then
              yum install -y -q nodejs npm >/dev/null 2>&1 && echo __OK__
            else
              echo __NO_PKG_MANAGER__
            fi
        """.trimIndent()
    }
}

/** 取输出尾部用于错误详情: stdout/stderr 合并后裁剪到 [maxChars], 过滤 __OK__ 标记 */
private fun me.rerere.workspace.WorkspaceCommandResult.combinedTail(maxChars: Int = 300): String =
    buildList {
        addAll(stdout.lineSequence())
        addAll(stderr.lineSequence())
    }
        .filter { it.isNotBlank() && !it.contains("__OK__") }
        .takeLast(8)
        .joinToString("\n")
        .take(maxChars)
