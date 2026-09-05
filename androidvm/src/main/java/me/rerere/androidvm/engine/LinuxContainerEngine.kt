package me.rerere.androidvm.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.androidvm.VirtualEngine
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmInstance
import me.rerere.androidvm.VmWorkspaceBridge
import me.rerere.androidvm.VmModuleInfo
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceManager
import java.io.File

/**
 * 基于 proot 的用户态 Linux 容器引擎。
 *
 * 复用 workspace 模块完成 rootfs 下载/解包与 shell 接入，可立即在真机运行 Linux 软件。
 * 该模式面向命令行工作负载，不具备 Android APK 运行能力（那是 [BlackBoxEngine] 的职责）。
 */
class LinuxContainerEngine(
    private val context: Context,
    private val bridge: VmWorkspaceBridge? = null,
) : VirtualEngine {
    override val type = VmEngineType.LINUX

    private val manager by lazy {
        WorkspaceManager(File(context.filesDir, "workspaces"))
    }
    private val installer by lazy { RootfsInstaller(manager) }

    override suspend fun provision(
        instance: VmInstance,
        onProgress: (Float, String) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            // 先在工作区数据库登记(root=实例 id), 终端/文件页据此才能打开;
            // 登记先于下载, 安装期间打开终端会提示 rootfs 未就绪, 装完即可用
            if (bridge != null && !bridge.ensureLinkedWorkspace(instance.id, instance.name)) {
                throw IllegalStateException("工作区登记失败，无法初始化容器")
            }
            installer.install(instance.id, instance.rootfsUrl) { p ->
                val ratio = when (p.stage) {
                    RootfsInstallStage.DOWNLOADING -> {
                        val total = p.totalBytes?.toFloat() ?: 0f
                        if (total > 0) 0.5f * (p.bytesRead.toFloat() / total) else 0.1f
                    }
                    RootfsInstallStage.EXTRACTING -> 0.5f + 0.5f * minOf(1f, p.entriesExtracted / 4000f)
                    RootfsInstallStage.INSTALLED -> 1f
                }
                onProgress(ratio, p.stage.name)
            }
            // 同步 shell 就绪状态: 工作区详情页(文件/工具/终端入口)据此解锁
            if (bridge != null) {
                runCatching { bridge.markShellReady(instance.id) }
                    .onFailure { me.rerere.androidvm.engine.EngineLog.warn("markShellReady failed: ${it.message}") }
            }
        }
    }

    override suspend fun launch(instance: VmInstance, packageName: String?) {
        // Linux 容器为命令行环境，应用启动由终端完成；此处仅确保 rootfs 就绪。
        if (!manager.hasRootfs(instance.id)) {
            throw IllegalStateException("容器尚未初始化，请先完成安装")
        }
    }

    override suspend fun installApp(instance: VmInstance, pathOrUrl: String): String {
        throw UnsupportedOperationException(
            "Linux 容器请通过终端使用包管理器安装软件（如 apt / apk add），暂不支持 APK 安装",
        )
    }

    override suspend fun listApps(instance: VmInstance): List<String> {
        return if (manager.hasRootfs(instance.id)) listOf("linux-shell") else emptyList()
    }

    override suspend fun installModule(instance: VmInstance, path: String): String {
        throw UnsupportedOperationException("Linux 容器不支持 Magisk/Xposed 模块，请在 Android 虚拟化实例中使用")
    }

    override suspend fun listModules(): List<VmModuleInfo> = emptyList()

    override suspend fun setModuleEnabled(moduleId: String, enabled: Boolean) {
        throw UnsupportedOperationException("Linux 容器不支持虚拟框架模块")
    }

    override suspend fun uninstallModule(moduleId: String) {
        throw UnsupportedOperationException("Linux 容器不支持虚拟框架模块")
    }

    override suspend fun clone(instance: VmInstance, newName: String): VmInstance {
        withContext(Dispatchers.IO) {
            val src = manager.linuxDir(instance.id)
            val dst = manager.linuxDir(newName)
            if (src.exists()) src.copyRecursively(dst, overwrite = true)
        }
        return instance.copy(
            id = newName,
            name = newName,
            createdAt = System.currentTimeMillis(),
        )
    }

    override suspend fun destroy(instance: VmInstance) {
        withContext(Dispatchers.IO) {
            // 优先走 bridge(连带删除工作区 DB 记录, 避免工作区页面残留幽灵条目);
            // bridge 缺失时退回直接删目录
            if (bridge != null) {
                runCatching { bridge.deleteLinkedWorkspace(instance.id) }
            } else {
                manager.deleteWorkspace(instance.id)
            }
        }
    }
}
