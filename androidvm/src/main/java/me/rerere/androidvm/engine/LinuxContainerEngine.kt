package me.rerere.androidvm.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.androidvm.VirtualEngine
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmInstance
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
class LinuxContainerEngine(private val context: Context) : VirtualEngine {
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

    override suspend fun setModuleEnabled(packageName: String, enabled: Boolean) {
        throw UnsupportedOperationException("Linux 容器不支持模块")
    }

    override suspend fun uninstallModule(packageName: String) {
        throw UnsupportedOperationException("Linux 容器不支持模块")
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
            manager.deleteWorkspace(instance.id)
        }
    }
}
