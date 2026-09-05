package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.WorkspaceMounts
import me.rerere.rikkahub.data.ai.agent.AcpEnvironmentManager
import me.rerere.rikkahub.data.ai.agent.AcpMcpServersBuilder
import me.rerere.rikkahub.data.ai.agent.AcpRuntime
import me.rerere.rikkahub.data.ai.agent.AcpSessionStore
import me.rerere.rikkahub.data.ai.agent.AgentInstallLogBus
import me.rerere.rikkahub.data.ai.agent.ScriptMcpBridge
import me.rerere.androidvm.VmWorkspaceBridge
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.GroupRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkflowRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceProcessRunner
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get(), get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = WorkspaceMounts.androidLocalMounts(context),
        )
    }

    single {
        RootfsInstaller(get())
    }

    // androidvm Linux 容器与工作区系统的桥接: 实例创建/销毁时同步工作区 DB 登记
    single<VmWorkspaceBridge> {
        val repository = get<WorkspaceRepository>()
        object : VmWorkspaceBridge {
            override suspend fun ensureLinkedWorkspace(id: String, name: String): Boolean =
                runCatching { repository.ensureLinkedWorkspace(id, name) }.isSuccess

            override suspend fun deleteLinkedWorkspace(id: String) {
                runCatching { repository.delete(id) }
            }

            override suspend fun markShellReady(id: String) {
                repository.markShellReady(id)
            }
        }
    }

    // 长驻进程 runner：供 ACP 平台 Agent 在 PRoot 容器内以交互式 stdio 子进程运行
    single {
        val context: Context = get()
        WorkspaceProcessRunner(
            baseDir = File(context.filesDir, "workspaces"),
            bindMounts = WorkspaceMounts.androidLocalMounts(context),
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }

    // Agent 安装日志总线: 安装命令输出实时流向「工作区终端」页面的日志面板
    single {
        AgentInstallLogBus()
    }

    single {
        AcpEnvironmentManager(get(), get(), get())
    }

    single {
        AcpRuntime(
            environmentManager = get(),
            processRunner = get(),
            json = get(),
            scope = get(),
            mcpServersBuilder = get(),
            sessionStore = AcpSessionStore(),
        )
    }

    single {
        AcpMcpServersBuilder(
            pluginManager = get(),
            scriptRuntime = get(),
            settingsStore = get(),
            scriptBridge = get(),
        )
    }

    single {
        ScriptMcpBridge(
            pluginManager = get(),
            scriptRuntime = get(),
            settingsStore = get(),
        )
    }

    single {
        WorkspaceRepository(get(), get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }

    single {
        WorkflowRepository(get(), get())
    }

    single {
        GroupRepository(get())
    }

    single<me.rerere.rikkahub.data.ai.group.GroupStore> {
        get<GroupRepository>()
    }

    single {
        me.rerere.rikkahub.data.plugin.PluginConfigRepository(get())
    }

    single {
        me.rerere.rikkahub.data.plugin.PluginManager(get(), get(), get(), get())
    }

    // Cordis 内核与插件桥接：供 agent-loop（阶段 5）与面板壳（阶段 7）使用
    // 共享事件总线：内核与 HostToolsSeam 复用，保证 tools/change 事件达插件监听器
    single<me.rerere.rikkahub.data.cordis.CordisEventBus> {
        me.rerere.rikkahub.data.cordis.CordisEventBus()
    }

    // 宿主能力缝实现：单例共享，同时注入 CordisHost 与 AgentHost
    single<me.rerere.rikkahub.data.cordis.LlmSeam> {
        me.rerere.rikkahub.data.cordis.HostLlmSeam(get(), get())
    }

    single<me.rerere.rikkahub.data.cordis.ToolsSeam> {
        me.rerere.rikkahub.data.cordis.HostToolsSeam(get(), get())
    }

    single<me.rerere.rikkahub.data.cordis.SystemPromptSeam> {
        me.rerere.rikkahub.data.cordis.HostSystemPromptSeam()
    }

    single<me.rerere.rikkahub.data.cordis.SessionsSeam> {
        // R2.3：sessions 缝经惰性提供者接 Room 事件表（构造零依赖，append 时落库）
        val koin = getKoin()
        me.rerere.rikkahub.data.cordis.HostSessionsSeam(
            sessionEventRepoProvider = { koin.get<me.rerere.rikkahub.data.session.SessionEventRepository>() },
        )
    }

    // 宿主事件总线：缓冲 AppEventBus 上的可感知事件，供面板 JS 增量轮询
    single {
        me.rerere.rikkahub.data.plugin.CordisHostEventBus(
            get(),
            get(),
        )
    }

    single<me.rerere.rikkahub.data.cordis.CordisHost> {
        me.rerere.rikkahub.data.cordis.CordisHost(
            llm = get(),
            tools = get(),
            sessions = get(),
            systemPrompt = get(),
        )
    }

    single {
        me.rerere.rikkahub.data.cordis.CordisKernel(get(), get())
    }

    // Agent 启动器：组合真实能力缝驱动 agent-loop（阶段 9）
    single {
        me.rerere.rikkahub.data.agent.AgentHost(get(), get(), get(), get())
    }

    single {
        // 重依赖惰性化（D1.1）：ChatService/ConversationRepository/AgentHost 构造链深
        // （Room/ProviderManager 等），且存在潜在环状引用；改为 Provider 式惰性解析，
        // Bridge 构造期零成本，首次 seamCall 才拉起，失败经 PluginBoundary/桥内
        // runCatching 降级为结构化错误，根治"进插件页面必崩"
        val koin = getKoin()
        val executor = me.rerere.rikkahub.data.plugin.CordisJsExecutor(get(), get())
        me.rerere.rikkahub.data.plugin.CordisPluginBridge(
            get(),
            { pluginId, toolName, args -> executor(pluginId, toolName, args) },
            agentHost = { koin.get<me.rerere.rikkahub.data.agent.AgentHost>() },
            settingsStore = get(),
            conversationRepo = { koin.get<me.rerere.rikkahub.data.repository.ConversationRepository>() },
            chatService = { koin.get<me.rerere.rikkahub.service.ChatService>() },
            eventBus = get(),
        )
    }

    // DSH/脚本插件运行时协调者：把已启用插件同步进 CordisKernel 并热插拔
    single {
        me.rerere.rikkahub.data.plugin.ScriptToolsSeamProducer(get(), get(), get(), get(), get())
    }

    single {
        me.rerere.rikkahub.data.plugin.CordisRuntimeHost(
            get(), get(), get(), get(), get(), get(), get()
        )
    }

    single<me.rerere.rikkahub.data.script.ScriptChatBridge> {
        me.rerere.rikkahub.data.chat.RikkaScriptChatBridge(get(), get(), get())
    }

    single {
        me.rerere.rikkahub.data.script.ScriptRuntime(get(), get(), get(), get())
    }

    single {
        me.rerere.rikkahub.data.api.GitHubPluginAPI.create(get())
    }

    single {
        me.rerere.rikkahub.data.api.PluginMarketDataSource(get(), get())
    }

    single {
        me.rerere.rikkahub.data.api.CommunityMarketDataSource.create(get(), get())
    }

    // 四个市场数据源统一在此注册（官方/社区/DSH/酒馆）
    single {
        me.rerere.rikkahub.data.api.DshMarketDataSource(httpClient = get())
    }
    single {
        me.rerere.rikkahub.data.api.TavernMarketDataSource(httpClient = get())
    }
}
