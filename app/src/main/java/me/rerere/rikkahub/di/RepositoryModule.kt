package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.WorkspaceMounts
import me.rerere.rikkahub.data.ai.agent.AcpEnvironmentManager
import me.rerere.rikkahub.data.ai.agent.AcpMcpServersBuilder
import me.rerere.rikkahub.data.ai.agent.AcpRuntime
import me.rerere.rikkahub.data.ai.agent.AgentInstallLogBus
import me.rerere.rikkahub.data.ai.agent.ScriptMcpBridge
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
        AcpEnvironmentManager(get(), get())
    }

    single {
        AcpRuntime(
            environmentManager = get(),
            processRunner = get(),
            json = get(),
            scope = get(),
            mcpServersBuilder = get(),
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
        me.rerere.rikkahub.data.plugin.PluginManager(get(), get())
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
}
