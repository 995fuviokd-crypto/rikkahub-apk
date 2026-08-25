package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.AgentPlatform
import me.rerere.workspace.WorkspaceManager

/** 平台 Agent 在指定工作区内的安装/就绪状态 */
enum class AgentEnvStatus {
    /** 尚未检测 */
    UNKNOWN,

    /** 运行时（node/npm）与 CLI 均已就绪 */
    READY,

    /** node/npm 缺失，需要先安装运行时 */
    NODE_MISSING,

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
    private val logBus: AgentInstallLogBus = AgentInstallLogBus(),
) {
    private val readyRoots = mutableSetOf<String>()

    /**
     * 检测 [platform] 在 [root] 工作区内的安装状态。纯只读，不触发网络下载。
     */
    suspend fun checkStatus(root: String, platform: AgentPlatform): AgentEnvStatus = withContext(Dispatchers.IO) {
        val cacheKey = "$root|${platform.name}"
        if (cacheKey in readyRoots) return@withContext AgentEnvStatus.READY
        if (!workspaceManager.hasRootfs(root)) return@withContext AgentEnvStatus.NO_ROOTFS
        if (!hasNode(root)) return@withContext AgentEnvStatus.NODE_MISSING
        if (!hasCli(root, platform)) return@withContext AgentEnvStatus.CLI_MISSING
        AgentEnvStatus.READY
    }

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
                                // 实时透出到终端页日志面板
                                logBus.append(platform, chunk)
                                // 脚本通过 echo __P__NN 上报里程碑, 累积解析避免标记被流块截断
                                Regex("__P__(\\d+)").findAll(chunk).forEach { match ->
                                    val marker = match.groupValues[1].toIntOrNull() ?: return@forEach
                                    if (marker > nodeStage) {
                                        nodeStage = marker
                                        report(
                                            AgentInstallPhase.INSTALLING_NODE,
                                            nodeDetail,
                                            5 + marker * 45 / 100, // 映射到整体 [5, 50]
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
                    // npmmirror 优先(国内直连快, 免去官方源超时等待), 官方源兜底;
                    // --no-audit --no-fund 省去安全审计与赞助信息请求;
                    // 保留默认输出级别让 npm 的下载/安装过程在终端页可见
                    val cliCommand = buildString {
                        append("npm install -g ${platform.cliPackage} --registry=$NPM_MIRROR_REGISTRY --no-audit --no-fund ")
                        append("--fetch-retries=2 --fetch-timeout=60000 2>&1 && echo __OK__ || ")
                        append("(npm install -g ${platform.cliPackage} --no-audit --no-fund ")
                        append("--fetch-retries=2 --fetch-timeout=60000 2>&1 && echo __OK__)")
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
            workspaceManager.writeRootfsBytes(root, IMPORT_ARCHIVE_PATH, bytes)

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
    fun cliCommand(platform: AgentPlatform, args: List<String>): List<String> = buildList {
        add("npx")
        add("-y")
        add(platform.cliPackage)
        addAll(args)
    }

    private fun hasNode(root: String): Boolean = runCommandOk(
        root,
        "command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && echo __OK__",
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
     */
    private fun ensureCliInstalled(root: String, platform: AgentPlatform) {
        val result = workspaceManager.executeCommand(
            root = root,
            command = "test -d \"/usr/local/lib/node_modules/${platform.cliPackage}\" && echo __OK__ || echo __NO__",
            timeoutMillis = CHECK_TIMEOUT_MS,
        )
        check(result.stdout.contains("__OK__")) { "CLI 校验失败：${platform.cliPackage} 未出现在全局 node_modules" }
    }

    private fun runCommandOk(root: String, command: String): Boolean = runCatching {
        val result = workspaceManager.executeCommand(
            root = root,
            command = command,
            timeoutMillis = CHECK_TIMEOUT_MS,
        )
        result.exitCode == 0 && result.stdout.contains("__OK__")
    }.getOrDefault(false)

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

        /** 离线导入包在工作区文件区的暂存路径(容器内挂载为 /workspace) */
        private const val IMPORT_ARCHIVE_PATH = "/.agent-import/package.tgz"
        private const val IMPORT_DIR_IN_CONTAINER = "/workspace/.agent-import"

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
                  # 预创建 npm 缓存目录结构, 防止后续安装时 PRoot 下 rename ENOENT
                  mkdir -p /root/.npm/_cacache/content-v2/sha512 /root/.npm/_cacache/tmp
                  echo __P__45
                  node --version >/dev/null 2>&1 && { echo __OK__; exit 0; }
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
