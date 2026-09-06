package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.AppScope
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "WorkspaceTerminalSessionManager"

/**
 * Owns workspace terminal sessions independently from the terminal page lifecycle.
 *
 * A page only attaches a [com.termux.view.TerminalView] to the selected tab. Navigating away
 * therefore keeps every shell and its screen buffer alive; a session is finished only when its
 * tab is explicitly closed (or the shell exits by itself).
 */
class WorkspaceTerminalSessionManager internal constructor(
    context: Context,
    private val appScope: AppScope,
) {
    private val appContext = context.applicationContext
    private val workspaceStates = MutableStateFlow<Map<String, WorkspaceTerminalTabsState>>(emptyMap())
    private val nextTabId = AtomicLong(1)
    private val creationJobs = mutableMapOf<String, Job>()
    private val workspaceLocalDirUris = mutableMapOf<String, String>()

    internal fun observeWorkspace(root: String): Flow<WorkspaceTerminalTabsState> =
        workspaceStates
            .map { states -> states[root] ?: WorkspaceTerminalTabsState() }
            .distinctUntilChanged()

    internal fun ensureSession(
        root: String,
        androidLocalAccess: Boolean = true,
        localDirectoryUri: String? = null,
    ) {
        launchCreateTab(
            root = root,
            onlyIfEmpty = true,
            androidLocalAccess = androidLocalAccess,
            localDirectoryUri = localDirectoryUri,
        )
    }

    internal fun createTab(
        root: String,
        androidLocalAccess: Boolean = true,
        localDirectoryUri: String? = null,
    ) {
        launchCreateTab(
            root = root,
            onlyIfEmpty = false,
            androidLocalAccess = androidLocalAccess,
            localDirectoryUri = localDirectoryUri,
        )
    }

    internal fun selectTab(root: String, tabId: Long) {
        updateState(root) { state ->
            if (state.tabs.none { it.id == tabId }) state else state.copy(selectedTabId = tabId)
        }
    }

    internal fun closeTab(root: String, tabId: Long) {
        var closedTab: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val closedIndex = state.tabs.indexOfFirst { it.id == tabId }
            if (closedIndex < 0) return@updateState state

            closedTab = state.tabs[closedIndex]
            val remainingTabs = state.tabs.filterNot { it.id == tabId }
            val selectedTabId = if (state.selectedTabId == tabId) {
                remainingTabs.getOrNull(closedIndex)?.id
                    ?: remainingTabs.getOrNull(closedIndex - 1)?.id
            } else {
                state.selectedTabId
            }
            state.copy(
                tabs = remainingTabs,
                selectedTabId = selectedTabId,
            )
        }

        // Remove it from observable state before finishing so the finish callback cannot put it
        // back into the UI while the selected TerminalView is being disposed.
        closedTab?.let { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }

        // 最后一个 Tab 关闭后把 /local 镜像变更写回 Android 本地目录
        if (currentState(root).tabs.isEmpty()) {
            syncLocalMirrorBack(root)
        }
    }

    /**
     * Stops all sessions owned by [root] before its rootfs is replaced or the workspace is deleted.
     */
    internal suspend fun closeWorkspace(root: String) = withContext(Dispatchers.Main.immediate) {
        // Wait for rootfs preparation to leave its IO section before callers delete or replace the
        // same files. CancellationException is deliberately rethrown by createTab().
        creationJobs[root]?.cancelAndJoin()

        val state = workspaceStates.getAndUpdate { states -> states - root }[root]
            ?: return@withContext
        state.tabs.forEach { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
        syncLocalMirrorBack(root)
    }

    /** 终端会话结束后把 /local 镜像目录的变更异步写回 SAF 本地目录 */
    private fun syncLocalMirrorBack(root: String) {
        val uri = workspaceLocalDirUris.remove(root) ?: return
        appScope.launch(Dispatchers.IO) {
            runCatching {
                syncTerminalLocalMirrorBack(appContext, root, uri)
            }.onFailure { error ->
                Log.e(TAG, "Failed to sync local mirror back for workspace $root", error)
            }
        }
    }

    private fun launchCreateTab(
        root: String,
        onlyIfEmpty: Boolean,
        androidLocalAccess: Boolean = true,
        localDirectoryUri: String? = null,
    ) {
        if (root in creationJobs) return
        if (!localDirectoryUri.isNullOrBlank()) {
            workspaceLocalDirUris[root] = localDirectoryUri
        }

        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                createTab(
                    root = root,
                    onlyIfEmpty = onlyIfEmpty,
                    androidLocalAccess = androidLocalAccess,
                    localDirectoryUri = localDirectoryUri,
                )
            } finally {
                creationJobs.remove(root, job)
            }
        }
        creationJobs[root] = job
        job.start()
    }

    private suspend fun createTab(
        root: String,
        onlyIfEmpty: Boolean,
        androidLocalAccess: Boolean = true,
        localDirectoryUri: String? = null,
    ) = withContext(Dispatchers.Main.immediate) {
        val initialState = currentState(root)
        if (initialState.isCreating || (onlyIfEmpty && initialState.tabs.isNotEmpty())) {
            return@withContext
        }
        updateState(root) { it.copy(isCreating = true) }

        val prepared = if (initialState.readiness == WorkspaceTerminalReadiness.Ready) {
            true
        } else {
            try {
                // 准备阶段(PRoot 修补 / 本地目录同步)加超时: 卡住时不再让 isCreating 永久占用,
                // 页面能回到可重试状态, 而不是一直停留在"正在加载"
                withTimeout(PREPARE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        if (!workspaceRootfsReady(appContext, root)) {
                            false
                        } else {
                            prepareWorkspaceTerminalSession(
                                context = appContext,
                                root = root,
                                androidLocalAccess = androidLocalAccess,
                                localDirectoryUri = localDirectoryUri,
                            )
                            true
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Prepare terminal for workspace $root failed or timed out: ${error.message}")
                false
            }
        }

        if (!prepared) {
            updateState(root) {
                it.copy(
                    readiness = if (workspaceRootfsReady(appContext, root)) {
                        WorkspaceTerminalReadiness.Loading
                    } else {
                        WorkspaceTerminalReadiness.NotInstalled
                    },
                    isCreating = false,
                )
            }
            return@withContext
        }

        val tabId = nextTabId.getAndIncrement()
        val tabNumber = currentState(root).nextTabNumber
        val client = WorkspaceTerminalSessionClient(appContext) {
            markFinished(root = root, tabId = tabId)
        }
        val session = runCatching {
            createWorkspaceTerminalSession(
                context = appContext,
                root = root,
                androidLocalAccess = androidLocalAccess,
                localDirectoryUri = localDirectoryUri,
                client = client,
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to create terminal for workspace $root", error)
        }.getOrNull()

        if (session == null) {
            updateState(root) { it.copy(isCreating = false) }
            return@withContext
        }

        val tab = WorkspaceTerminalTab(
            id = tabId,
            number = tabNumber,
            session = session,
            client = client,
        )
        updateState(root) { state ->
            state.copy(
                tabs = state.tabs + tab,
                selectedTabId = tab.id,
                readiness = WorkspaceTerminalReadiness.Ready,
                isCreating = false,
                nextTabNumber = tabNumber + 1,
            )
        }
    }

    private fun markFinished(root: String, tabId: Long) {
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(finished = true) else tab
                },
            ))
        }
    }

    private fun currentState(root: String): WorkspaceTerminalTabsState =
        workspaceStates.value[root] ?: WorkspaceTerminalTabsState()

    private inline fun updateState(
        root: String,
        transform: (WorkspaceTerminalTabsState) -> WorkspaceTerminalTabsState,
    ) {
        workspaceStates.update { states ->
            states + (root to transform(states[root] ?: WorkspaceTerminalTabsState()))
        }
    }

    private companion object {
        const val TAG = "WorkspaceTerminalManager"

        /** 终端会话准备(PRoot 修补/本地目录同步)超时: 超时后回到可重试状态, 避免永久"正在加载" */
        const val PREPARE_TIMEOUT_MS = 60_000L
    }
}

internal data class WorkspaceTerminalTabsState(
    val tabs: List<WorkspaceTerminalTab> = emptyList(),
    val selectedTabId: Long? = null,
    val readiness: WorkspaceTerminalReadiness = WorkspaceTerminalReadiness.Loading,
    val isCreating: Boolean = false,
    val nextTabNumber: Int = 1,
)

internal data class WorkspaceTerminalTab(
    val id: Long,
    val number: Int,
    val session: TerminalSession,
    val client: WorkspaceTerminalSessionClient,
    val finished: Boolean = false,
)

internal enum class WorkspaceTerminalReadiness {
    Loading,
    Ready,
    NotInstalled,
}
