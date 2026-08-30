package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
    private val acpEnvironmentManager: me.rerere.rikkahub.data.ai.agent.AcpEnvironmentManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    init {
        loadWorkspace()
        refresh()
        // 环境就绪后自动检测开发工具安装状态
        viewModelScope.launch {
            _state
                .map { it.workspace?.shellStatus }
                .distinctUntilChanged()
                .collect { status ->
                    if (status == WorkspaceShellStatus.READY.name) {
                        detectDevTools()
                    }
                }
        }
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun setAndroidLocalAccess(enabled: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setAndroidLocalAccess(workspace.id, enabled)
            loadWorkspace()
        }
    }

    fun setLocalDirectory(uri: String?) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setLocalDirectory(workspace.id, uri)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    // ---------- 开发工具安装 ----------

    private val _devTools = MutableStateFlow<List<DevToolState>>(emptyList())
    val devTools = _devTools.asStateFlow()

    private val _devToolsChecking = MutableStateFlow(false)
    val devToolsChecking = _devToolsChecking.asStateFlow()

    private val _devToolsInstallingAll = MutableStateFlow(false)
    val devToolsInstallingAll = _devToolsInstallingAll.asStateFlow()

    /** 检测开发工具安装状态：对每个工具执行 command -v <cmd> */
    fun detectDevTools() {
        val workspace = _state.value.workspace ?: return
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return
        if (_devToolsChecking.value) return
        viewModelScope.launch {
            _devToolsChecking.value = true
            val current = _devTools.value.ifEmpty {
                DEV_TOOLS.map { DevToolState(tool = it) }
            }
            _devTools.value = current.map { it.copy(checking = true) }
            // 单条命令批量检测全部工具，避免每个工具都启动一次 PRoot 进程导致页面卡死。
            // 只用 command -v（覆盖 PATH）做检测；去掉 find 全盘遍历，避免大 rootfs 上检测超时卡死。
            val commands = current.map { it.tool.command }
            val detection = runCatching {
                withTimeoutOrNull(DETECT_TIMEOUT_MS) {
                    repository.executeCommand(
                        workspace.id,
                        buildString {
                            append("for c in ")
                            append(commands.joinToString(" ") { shellQuote(it) })
                            append("; do if command -v \"${'$'}c\" >/dev/null 2>&1; ")
                            append("then echo \"FOUND:${'$'}c\"; else echo \"MISSING:${'$'}c\"; fi; done")
                        }
                    )
                }
            }.getOrNull()
            val found = detection?.stdout.orEmpty()
                .lineSequence()
                .filter { it.startsWith("FOUND:") }
                .mapNotNull { it.removePrefix("FOUND:").trim().takeIf { it.isNotEmpty() } }
                .toSet()
            val detected = current.map { state ->
                state.copy(
                    checking = false,
                    // 多条命令共用同一检测命令（如 nodejs 用 "node" 检测但包内含 npm）时，
                    // 只要其中任意一条命令存在即视为已安装
                    installed = state.tool.command.split(" ").any { it in found },
                    error = null,
                )
            }
            _devTools.value = detected
            _devToolsChecking.value = false
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** 单个安装开发工具 */
    fun installDevTool(id: String) {
        val workspace = _state.value.workspace ?: return
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return
        viewModelScope.launch {
            _devTools.update { list ->
                list.map { if (it.tool.id == id) it.copy(installing = true, error = null) else it }
            }
            val tool = _devTools.value.find { it.tool.id == id } ?: return@launch
            val result = executeToolInstall(workspace.id, tool.tool, tool.selectedVersion)
            _devTools.update { list ->
                list.map {
                    if (it.tool.id == id) {
                        it.copy(
                            installing = false,
                            installed = result.first,
                            error = result.second,
                        )
                    } else it
                }
            }
        }
    }

    /** 选择某个工具要安装的版本（仅支持可选版本的工具） */
    fun selectDevToolVersion(id: String, version: String) {
        _devTools.update { list ->
            list.map { if (it.tool.id == id) it.copy(selectedVersion = version) else it }
        }
    }

    /** 一键安装所有未安装的开发工具 */
    fun installAllDevTools() {
        val workspace = _state.value.workspace ?: return
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return
        if (_devToolsInstallingAll.value) return
        viewModelScope.launch {
            _devToolsInstallingAll.value = true
            val missing = _devTools.value.filter { !it.installed && !it.installing }
            if (missing.isNotEmpty()) {
                for (state in missing) {
                    _devTools.update { list ->
                        list.map {
                            if (it.tool.id == state.tool.id) it.copy(installing = true, error = null) else it
                        }
                    }
                    val (ok, error) = executeToolInstall(workspace.id, state.tool, state.selectedVersion)
                    _devTools.update { list ->
                        list.map {
                            if (it.tool.id == state.tool.id) {
                                it.copy(installing = false, installed = ok, error = error)
                            } else it
                        }
                    }
                }
            }
            _devToolsInstallingAll.value = false
        }
    }

    /** 执行安装命令：优先自定义脚本，否则走 apt/apk；失败自动重试；返回 (是否成功, 错误信息) */
    private suspend fun executeToolInstall(
        workspaceId: String,
        tool: DevToolDef,
        version: String?,
    ): Pair<Boolean, String?> {
        val script = tool.installScript
        if (script != null) {
            return executeScriptWithRetry(workspaceId, tool, script, version)
        }
        // Node.js: 优先使用 APK 内置离线运行时(assets/offline/node), 彻底免去联网安装失败
        if (tool.id == "nodejs") {
            val root = _state.value.workspace?.root
            if (root != null) {
                val offlineOk = runCatching { acpEnvironmentManager.installNodeOfflineOnly(root) }.getOrDefault(false)
                if (offlineOk) {
                    // 离线解压后软链到 /usr/local/bin, 校验 node/npm 可用
                    val verify = runCatching {
                        repository.executeCommand(
                            workspaceId,
                            "command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && echo __OK__ || echo __NO__",
                        )
                    }.getOrNull()
                    if (verify?.exitCode == 0 && verify.stdout.contains("__OK__")) {
                        return true to null
                    }
                }
                // 离线不可用时回退到 apt/apk（错误仅在联网路径也失败时返回）
            }
        }
        // apt/apk 包安装：OpenJDK 根据所选版本渲染包名
        val resolvedPackage = if (tool.id == "openjdk") {
            val v = version ?: tool.versions.firstOrNull() ?: "17"
            "openjdk-$v-jdk-headless"
        } else {
            tool.packageName
        }
        val apkPackage = ALPINE_PACKAGE_MAP[tool.id] ?: resolvedPackage
        return executePackageWithRetry(workspaceId, tool, resolvedPackage, apkPackage)
    }

    private suspend fun executeScriptWithRetry(
        workspaceId: String,
        tool: DevToolDef,
        script: String,
        version: String?,
    ): Pair<Boolean, String?> {
        val rendered = script
            .replace("{{VERSION}}", version ?: tool.versions.firstOrNull() ?: "")
            .trimIndent()
        var lastError: String? = null
        repeat(3) { attempt ->
            val result = runCatching {
                repository.executeCommand(workspaceId, rendered, timeoutMillis = TOOL_INSTALL_TIMEOUT_MS)
            }.getOrNull()
            val ok = result?.exitCode == 0 && result.stdout.contains("__OK__")
            if (ok) return true to null
            lastError = when {
                result == null -> "命令执行异常"
                result.timedOut -> "安装超时（已自动重试）"
                else -> result.stderr.ifBlank { result.stdout }.take(200).ifBlank { "安装失败（退出码 ${result.exitCode}）" }
            }
            if (attempt < 2) delay(TOOL_INSTALL_RETRY_DELAY_MS)
        }
        return false to lastError
    }

    private suspend fun executePackageWithRetry(
        workspaceId: String,
        tool: DevToolDef,
        packageName: String,
        apkPackage: String,
    ): Pair<Boolean, String?> {
        var lastError: String? = null
        repeat(3) { attempt ->
            val result = runCatching {
                repository.executeCommand(
                    workspaceId,
                    buildString {
                        append("export DEBIAN_FRONTEND=noninteractive; ")
                        append("if command -v apt-get >/dev/null 2>&1; then ")
                        append("(apt-get update -qq >/dev/null 2>&1 || true); ")
                        append("apt-get install -y -qq $packageName >/dev/null 2>&1 && echo __OK__; ")
                        append("elif command -v apk >/dev/null 2>&1; then ")
                        append("apk add --no-cache -q $apkPackage >/dev/null 2>&1 && echo __OK__; ")
                        append("else echo __NO_PKG_MANAGER__; fi")
                    },
                    timeoutMillis = TOOL_INSTALL_TIMEOUT_MS,
                )
            }.getOrNull()
            val ok = result?.exitCode == 0 && result.stdout.contains("__OK__")
            if (ok) return true to null
            lastError = when {
                result == null -> "命令执行异常"
                result.timedOut -> "安装超时（已自动重试）"
                result.exitCode == 0 && result.stdout.contains("__NO_PKG_MANAGER__") ->
                    "未找到包管理器（仅支持 apt/apk）"
                else -> result.stderr.ifBlank { result.stdout }.take(200).ifBlank { "安装失败（退出码 ${result.exitCode}）" }
            }
            if (attempt < 2) delay(TOOL_INSTALL_RETRY_DELAY_MS)
        }
        return false to lastError
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}

/** 开发工具定义：apt/apk 包名与检测命令 */
data class DevToolDef(
    val id: String,
    val name: String,
    val description: String,
    val packageName: String,
    val command: String,
    /** 自定义安装脚本（在 Rootfs 内执行）。非空时优先于 apt/apk 包安装 */
    val installScript: String? = null,
    /** 可选版本列表；为空表示不支持选版本。选中后渲染到 [installScript]/[packageName] */
    val versions: List<String> = emptyList(),
)

data class DevToolState(
    val tool: DevToolDef,
    val installed: Boolean = false,
    val checking: Boolean = false,
    val installing: Boolean = false,
    val error: String? = null,
    val selectedVersion: String? = null,
)

/** 开发工具安装单次命令超时（apt 更新 + 大文件下载需要较长时间） */
private const val TOOL_INSTALL_TIMEOUT_MS = 10 * 60 * 1000L

/** 开发工具检测命令超时：只做 command -v，正常应毫秒级返回，超时兜底避免页面卡死 */
private const val DETECT_TIMEOUT_MS = 15_000L

/** 安装失败重试间隔 */
private const val TOOL_INSTALL_RETRY_DELAY_MS = 1_500L

/**
 * 多源下载函数: 按候选 URL 顺序逐个尝试(优先直连, 失败自动切国内镜像)。
 * gh-proxy 仅代理 GitHub 域名, dl.google.com/maven.google.com 需用腾讯/阿里镜像兜底。
 */
private val DOWNLOAD_HELPER_SCRIPT = """
dl() {
  out="$1"
  shift
  for url in "${'$'}@"; do
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL --connect-timeout 5 --max-time 900 -o "${'$'}out" "${'$'}url" && return 0
    else
      wget -q -T 5 --tries=1 -O "${'$'}out" "${'$'}url" && return 0
    fi
    rm -f "${'$'}out"
  done
  return 1
}
""".trimIndent()

/** Android SDK Build-Tools 安装脚本（aapt/aapt2/zipalign/apksigner/d8），从 Google 官方镜像下载 */
internal val ANDROID_BUILD_TOOLS_INSTALL_SCRIPT = """
set -e
BT_DIR=/opt/android/build-tools
BT_VERSION=34
mkdir -p "${'$'}BT_DIR"
${DOWNLOAD_HELPER_SCRIPT.trimIndent()}
# 自举下载与解压工具: Rootfs 默认无 curl/wget/unzip, 缺失时先经 apt/apk 补装
ensure() {
  if command -v apt-get >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get update -qq >/dev/null 2>&1 || true
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${'$'}@" >/dev/null 2>&1 || true
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache -q "${'$'}@" >/dev/null 2>&1 || true
  fi
}
if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
  ensure curl wget
fi
if ! command -v unzip >/dev/null 2>&1; then
  ensure unzip
fi
command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 || { echo "no curl/wget available"; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo "unzip missing"; exit 1; }
if ! command -v java >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get update -qq >/dev/null 2>&1 || true
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jre-headless >/dev/null 2>&1 || true
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache -q openjdk17-jre >/dev/null 2>&1 || true
  fi
fi
if [ ! -x "${'$'}BT_DIR/aapt2" ]; then
  ZIP=/tmp/build-tools-${'$'}BT_VERSION.zip
  dl "${'$'}ZIP" \
    "https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r${'$'}BT_VERSION-linux.zip" \
    "https://dl.google.com/android/repository/build-tools_r${'$'}BT_VERSION-linux.zip" \
    || { echo "all download sources failed for build-tools_r${'$'}BT_VERSION-linux.zip"; exit 1; }
  unzip -qo "${'$'}ZIP" -d "${'$'}BT_DIR" >/dev/null 2>&1 || { echo "unzip failed"; exit 1; }
  rm -f "${'$'}ZIP"
  for bin in aapt aapt2 zipalign apksigner d8; do
    found=$(find "${'$'}BT_DIR" -type f -name "${'$'}bin" 2>/dev/null | head -n1)
    if [ -n "${'$'}found" ]; then
      chmod +x "${'$'}found"
      ln -sf "${'$'}found" /usr/local/bin/"${'$'}bin"
    fi
  done
fi
command -v aapt2 >/dev/null 2>&1 || { echo "aapt2 missing"; exit 1; }
echo __OK__
""".trimIndent()

/** Android platform android.jar 安装脚本，版本通过 {{VERSION}} 占位符注入 */
internal val ANDROID_PLATFORM_INSTALL_SCRIPT = """
set -e
PLAT_DIR=/opt/android/platforms
V={{VERSION}}
mkdir -p "${'$'}PLAT_DIR"
${DOWNLOAD_HELPER_SCRIPT.trimIndent()}
# 自举下载与解压工具: Rootfs 默认无 curl/wget/unzip, 缺失时先经 apt/apk 补装
ensure() {
  if command -v apt-get >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get update -qq >/dev/null 2>&1 || true
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${'$'}@" >/dev/null 2>&1 || true
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache -q "${'$'}@" >/dev/null 2>&1 || true
  fi
}
if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
  ensure curl wget
fi
if ! command -v unzip >/dev/null 2>&1; then
  ensure unzip
fi
command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 || { echo "no curl/wget available"; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo "unzip missing"; exit 1; }
if [ ! -f "${'$'}PLAT_DIR/android.jar" ]; then
  # 官方 platform 包文件名带 ext 变体(如 platform-34-ext7_r02.zip), 按版本映射
  case "${'$'}V" in
    34) NAME=platform-34-ext7_r02.zip ;;
    35) NAME=platform-35_r02.zip ;;
    *) NAME="platform-${'$'}{V}_r02.zip" ;;
  esac
  ZIP=/tmp/platform-${'$'}V.zip
  dl "${'$'}ZIP" \
    "https://mirrors.cloud.tencent.com/AndroidSDK/${'$'}NAME" \
    "https://dl.google.com/android/repository/${'$'}NAME" \
    || { echo "all download sources failed for ${'$'}NAME"; exit 1; }
  unzip -qo "${'$'}ZIP" -d "${'$'}PLAT_DIR" >/dev/null 2>&1 || { echo "unzip failed"; exit 1; }
  rm -f "${'$'}ZIP"
  jar=$(find "${'$'}PLAT_DIR" -type f -name android.jar 2>/dev/null | head -n1)
  [ -n "${'$'}jar" ] && cp "${'$'}jar" "${'$'}PLAT_DIR/android.jar"
fi
[ -f "${'$'}PLAT_DIR/android.jar" ] || { echo "android.jar missing"; exit 1; }
echo __OK__
""".trimIndent()

/** D8/R8 安装脚本（r8lib.jar + /usr/local/bin/r8 包装器），从 Google Maven 下载，版本通过 {{VERSION}} 占位符注入 */
internal val R8_INSTALL_SCRIPT = """
set -e
R8_DIR=/opt/r8
V={{VERSION}}
mkdir -p "${'$'}R8_DIR"
${DOWNLOAD_HELPER_SCRIPT.trimIndent()}
# 自举下载工具: Rootfs 默认无 curl/wget, 缺失时先经 apt/apk 补装
if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get update -qq >/dev/null 2>&1 || true
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl >/dev/null 2>&1 || true
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache -q curl >/dev/null 2>&1 || true
  fi
fi
command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 || { echo "no curl/wget available"; exit 1; }
if ! command -v java >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get update -qq >/dev/null 2>&1 || true
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jre-headless >/dev/null 2>&1 || true
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache -q openjdk17-jre >/dev/null 2>&1 || true
  fi
fi
if [ ! -f "${'$'}R8_DIR/r8.jar" ]; then
  dl "${'$'}R8_DIR/r8.jar" \
    "https://maven.aliyun.com/repository/google/com/android/tools/r8/${'$'}V/r8-${'$'}V.jar" \
    "https://maven.google.com/com/android/tools/r8/${'$'}V/r8-${'$'}V.jar" \
    || { echo "all download sources failed for r8-${'$'}V.jar"; exit 1; }
fi
printf '#!/bin/sh\nexec java -jar /opt/r8/r8.jar "${'$'}@"\n' > /usr/local/bin/r8
chmod +x /usr/local/bin/r8
command -v java >/dev/null 2>&1 || { echo "java missing"; exit 1; }
echo __OK__
""".trimIndent()

/** Alpine(apk) 与 Debian/Ubuntu(apt) 包名差异映射；key 为 DevToolDef.id */
private val ALPINE_PACKAGE_MAP = mapOf(
    "nodejs" to "nodejs npm",
    "build-essential" to "build-base",
    "openssh-client" to "openssh",
    "ripgrep" to "ripgrep",
    "openjdk" to "openjdk17",
)

/** 工作区一键安装的常用开发工具（检测命令为 command -v <command>） */
val DEV_TOOLS = listOf(    DevToolDef("python3", "Python 3", "Python 解释器与脚本运行环境", "python3", "python3"),
    DevToolDef("nodejs", "Node.js", "JavaScript 运行时（含 npm）", "nodejs npm", "node"),
    DevToolDef("git", "Git", "分布式版本控制", "git", "git"),
    DevToolDef("curl", "curl", "HTTP 请求工具", "curl", "curl"),
    DevToolDef("wget", "wget", "网络下载工具", "wget", "wget"),
    DevToolDef("unzip", "unzip", "ZIP 解压工具", "unzip", "unzip"),
    DevToolDef("jq", "jq", "JSON 命令行处理器", "jq", "jq"),
    DevToolDef("ripgrep", "ripgrep", "高速文本搜索（rg）", "ripgrep", "rg"),
    DevToolDef("build-essential", "构建工具链", "GCC/G++ 与 make 编译环境", "build-essential", "gcc"),
    DevToolDef("ffmpeg", "FFmpeg", "音视频处理工具", "ffmpeg", "ffmpeg"),
    DevToolDef("openssh-client", "SSH 客户端", "远程连接与文件传输", "openssh-client", "ssh"),
    DevToolDef("vim", "Vim", "文本编辑器", "vim", "vim"),
    DevToolDef(
        "openjdk",
        "OpenJDK",
        "Java 运行时与开发工具包（JDK）",
        "openjdk-17-jdk-headless",
        "java",
        versions = listOf("17", "21"),
    ),
    DevToolDef(
        "android-sdk",
        "Android SDK Build-Tools",
        "aapt/aapt2、zipalign、apksigner、d8 等构建工具",
        "",
        "aapt2",
        installScript = ANDROID_BUILD_TOOLS_INSTALL_SCRIPT,
    ),
    DevToolDef(
        "android-platform",
        "Android platform android.jar",
        "Android API 平台库（编译与签名用）",
        "",
        "android.jar",
        installScript = ANDROID_PLATFORM_INSTALL_SCRIPT,
        versions = listOf("34", "35"),
    ),
    DevToolDef(
        "r8",
        "D8/R8",
        "Dex 编译器与代码压缩/混淆工具（r8lib.jar）",
        "",
        "r8",
        installScript = R8_INSTALL_SCRIPT,
        versions = listOf("8.2.33", "8.3.37"),
    ),
    DevToolDef("llvm", "LLVM (llvm-strip)", "llvm-strip 等 LLVM 二进制工具", "llvm", "llvm-strip"),
)
