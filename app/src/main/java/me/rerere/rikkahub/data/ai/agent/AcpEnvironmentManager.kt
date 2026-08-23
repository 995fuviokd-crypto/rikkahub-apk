package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** 安装进度事件；progress 为 null 时表示该阶段为不定进度 */
data class AgentInstallProgress(
    val phase: AgentInstallPhase,
    val detail: String = "",
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
     * 通过 [onProgress] 回报进度。安装完成/已缓存时返回成功。
     */
    suspend fun installWithProgress(
        root: String,
        platform: AgentPlatform,
        onProgress: (AgentInstallProgress) -> Unit = {},
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val cacheKey = "$root|${platform.name}"
            if (cacheKey in readyRoots) return@withContext Unit

            onProgress(AgentInstallProgress(AgentInstallPhase.CHECKING, "正在检测工作区环境…"))
            if (!workspaceManager.hasRootfs(root)) {
                throw IllegalStateException("工作区根文件系统未安装，请先在对应工作区安装系统环境")
            }

            if (!hasNode(root)) {
                onProgress(
                    AgentInstallProgress(
                        AgentInstallPhase.INSTALLING_NODE,
                        "正在安装 Node.js 与 npm（可能需要数分钟）…",
                    )
                )
                runWithRetry("node/npm") {
                    workspaceManager.executeCommand(
                        root = root,
                        command = NODE_INSTALL_SCRIPT,
                        timeoutMillis = INSTALL_TIMEOUT_MS,
                    )
                } ?: error("node/npm 安装失败，请重试")
                check(hasNode(root)) { "node/npm 安装后校验失败" }
            }

            if (!hasCli(root, platform)) {
                onProgress(
                    AgentInstallProgress(
                        AgentInstallPhase.INSTALLING_CLI,
                        "正在下载并安装 ${platform.cliPackage}（可能需要数分钟）…",
                    )
                )
                // 官方 registry 失败后自动降级 npmmirror 国内镜像重试（npm 源被墙时的兜底）
                runWithRetry(platform.cliPackage) {
                    workspaceManager.executeCommand(
                        root = root,
                        command = buildString {
                            append("npm install -g ${platform.cliPackage} 2>&1 && echo __OK__ || ")
                            append("(npm install -g ${platform.cliPackage} --registry=$NPM_MIRROR_REGISTRY 2>&1 && echo __OK__) || true")
                        },
                        timeoutMillis = INSTALL_TIMEOUT_MS,
                    )
                } ?: error("CLI 安装失败：${platform.cliPackage}（官方源与国内镜像均失败，请检查网络）")
                check(hasCli(root, platform)) { "CLI 安装后校验失败" }
            }

            onProgress(AgentInstallProgress(AgentInstallPhase.VERIFYING, "正在校验安装结果…"))
            ensureCliRunnable(root, platform)

            readyRoots += cacheKey
            onProgress(AgentInstallProgress(AgentInstallPhase.DONE, "安装完成"))
            Log.i(TAG, "environment ready for ${platform.name} in $root")
            Unit
        }
    }.onFailure { error ->
        Log.w(TAG, "install failed for ${platform.name} in $root", error)
        onProgress(AgentInstallProgress(AgentInstallPhase.FAILED, error.message ?: "安装失败"))
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

    private fun ensureCliRunnable(root: String, platform: AgentPlatform) {
        // --no-install 强制仅使用本地/全局缓存，避免再次触发网络下载
        val result = workspaceManager.executeCommand(
            root = root,
            command = "npx --no-install ${platform.cliPackage} --version >/dev/null 2>&1 && echo __OK__ || echo __NO__",
            timeoutMillis = CHECK_TIMEOUT_MS,
        )
        check(result.stdout.contains("__OK__")) { "CLI 无法启动：${platform.cliPackage}" }
    }

    private fun runCommandOk(root: String, command: String): Boolean = runCatching {
        val result = workspaceManager.executeCommand(
            root = root,
            command = command,
            timeoutMillis = CHECK_TIMEOUT_MS,
        )
        result.exitCode == 0 && result.stdout.contains("__OK__")
    }.getOrDefault(false)

    private fun runWithRetry(
        label: String,
        block: () -> me.rerere.workspace.WorkspaceCommandResult,
    ): me.rerere.workspace.WorkspaceCommandResult? {
        var lastError: String? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = runCatching(block).getOrNull()
            val ok = result?.exitCode == 0 && result.stdout.contains("__OK__")
            if (ok) return result
            lastError = when {
                result == null -> "命令执行异常"
                result.timedOut -> "安装超时"
                else -> result.stderr.ifBlank { result.stdout }.take(200)
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                Log.w(TAG, "install $label attempt ${attempt + 1} failed: $lastError, retrying…")
                kotlinx.coroutines.runBlocking { delay(RETRY_DELAY_MS) }
            }
        }
        return null
    }

    /** Forgets cached readiness, e.g. when the workspace rootfs is reinstalled. */
    fun invalidate(root: String) {
        readyRoots.removeAll { it.startsWith("$root|") }
    }

    companion object {
        private const val TAG = "AcpEnvironmentManager"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 3_000L
        private const val CHECK_TIMEOUT_MS = 60_000L
        private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L

        /** npmmirror（原淘宝）npm 镜像，官方 registry 不可达时的降级源 */
        private const val NPM_MIRROR_REGISTRY = "https://registry.npmmirror.com"

        private val NODE_INSTALL_SCRIPT = """
            export DEBIAN_FRONTEND=noninteractive;
            if command -v apt-get >/dev/null 2>&1; then
              (apt-get update -qq >/dev/null 2>&1 || true);
              apt-get install -y -qq nodejs npm >/dev/null 2>&1 && echo __OK__;
            elif command -v apk >/dev/null 2>&1; then
              apk add --no-cache -q nodejs npm >/dev/null 2>&1 && echo __OK__;
            elif command -v yum >/dev/null 2>&1; then
              yum install -y -q nodejs npm >/dev/null 2>&1 && echo __OK__;
            else
              echo __NO_PKG_MANAGER__;
            fi
        """.trimIndent()
    }
}
