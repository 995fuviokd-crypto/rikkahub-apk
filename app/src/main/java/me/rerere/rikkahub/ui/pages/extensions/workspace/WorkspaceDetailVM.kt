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
import kotlinx.coroutines.launch
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
            val detected = current.map { state ->
                val ok = runCatching {
                    repository.executeCommand(
                        workspace.id,
                        "command -v ${state.tool.command} >/dev/null 2>&1 && echo __FOUND__",
                    )
                }.getOrNull()?.exitCode == 0
                state.copy(checking = false, installed = ok, error = null)
            }
            _devTools.value = detected
            _devToolsChecking.value = false
        }
    }

    /** 单个安装开发工具 */
    fun installDevTool(id: String) {
        val workspace = _state.value.workspace ?: return
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return
        viewModelScope.launch {
            _devTools.update { list ->
                list.map { if (it.tool.id == id) it.copy(installing = true, error = null) else it }
            }
            val tool = _devTools.value.find { it.tool.id == id } ?: return@launch
            val result = executeToolInstall(workspace.id, tool.tool)
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
                    val (ok, error) = executeToolInstall(workspace.id, state.tool)
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

    /** 执行安装命令，返回 (是否成功, 错误信息) */
    private suspend fun executeToolInstall(workspaceId: String, tool: DevToolDef): Pair<Boolean, String?> {
        return runCatching {
            val command = buildString {
                append("export DEBIAN_FRONTEND=noninteractive; ")
                append("if command -v apt-get >/dev/null 2>&1; then ")
                append("(apt-get update -qq >/dev/null 2>&1 || true); ")
                append("apt-get install -y -qq ${tool.packageName} >/dev/null 2>&1 && echo __OK__; ")
                append("elif command -v apk >/dev/null 2>&1; then ")
                append("apk add --no-cache -q ${tool.packageName} >/dev/null 2>&1 && echo __OK__; ")
                append("else echo __NO_PKG_MANAGER__; fi")
            }
            val result = repository.executeCommand(workspaceId, command)
            when {
                result.exitCode == 0 && result.stdout.contains("__OK__") -> true to null
                result.exitCode == 0 && result.stdout.contains("__NO_PKG_MANAGER__") -> {
                    false to "未找到包管理器（仅支持 apt/apk）"
                }
                result.timedOut -> false to "安装超时，请稍后在终端中重试"
                else -> false to (result.stderr.ifBlank { result.stdout }.take(200).ifBlank { "安装失败（退出码 ${result.exitCode}）" })
            }
        }.getOrElse { e ->
            false to (e.message ?: "安装失败")
        }
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
)

data class DevToolState(
    val tool: DevToolDef,
    val installed: Boolean = false,
    val checking: Boolean = false,
    val installing: Boolean = false,
    val error: String? = null,
)

/** 工作区一键安装的常用开发工具（检测命令为 command -v <command>） */
val DEV_TOOLS = listOf(
    DevToolDef("python3", "Python 3", "Python 解释器与脚本运行环境", "python3", "python3"),
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
)
