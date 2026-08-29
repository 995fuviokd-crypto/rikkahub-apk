package me.rerere.androidvm

/**
 * 虚拟化引擎契约。
 *
 * 统一抽象两种运行模式：用户态 Linux 容器（[me.rerere.androidvm.engine.LinuxContainerEngine]）
 * 与仿光速的 Android 应用虚拟化（[me.rerere.androidvm.engine.BlackBoxEngine]）。
 *
 * 设计目标：前端 UI 只依赖本契约，不关心底层是 proot 还是 FBlackBox；
 * 当 FBlackBox 引擎在真机完成接入后，UI 无需改动即可获得完整 Android 虚拟化能力。
 */
interface VirtualEngine {
    val type: VmEngineType

    /** 创建并初始化实例（下载/解包 rootfs）。返回安装进度回调。 */
    suspend fun provision(
        instance: VmInstance,
        onProgress: (Float, String) -> Unit,
    )

    /** 启动实例内的某个应用（Android 模式为 APK；Linux 模式为终端或命令）。 */
    suspend fun launch(instance: VmInstance, packageName: String? = null)

    /** 向实例内安装一个 APK / 软件包。 */
    suspend fun installApp(instance: VmInstance, pathOrUrl: String): String

    /** 列出实例内已安装应用。 */
    suspend fun listApps(instance: VmInstance): List<String>

    /**
     * 安装虚拟框架模块（Xposed/Magisk 格式模块在 Bcore 虚拟空间内加载）。
     * 返回模块包名。Linux 容器不支持，抛 [UnsupportedOperationException]。
     */
    suspend fun installModule(instance: VmInstance, path: String): String

    /** 列出已安装模块。 */
    suspend fun listModules(): List<VmModuleInfo>

    /** 启用/停用模块（Xposed 包名或 Magisk 模块 id）。 */
    suspend fun setModuleEnabled(moduleId: String, enabled: Boolean)

    /** 卸载模块（Xposed 包名或 Magisk 模块 id）。 */
    suspend fun uninstallModule(moduleId: String)

    /** 克隆实例（应用隔离/多开）。 */
    suspend fun clone(instance: VmInstance, newName: String): VmInstance

    /** 删除实例（清理 rootfs 等产物）。 */
    suspend fun destroy(instance: VmInstance)

    /**
     * 重启客机运行态（仅客机 ROM 路线有意义：杀客机 PID1 后重新 boot）。
     * 默认空实现：Linux/BlackBox 无独立内核，重启即下次启动生效。
     */
    suspend fun rebootGuest(instance: VmInstance) = Unit

    /**
     * 设置虚拟 root 开关（Android 虚拟化才有意义）。
     * 默认空实现：Linux / 客机 ROM 不处理。
     */
    suspend fun setVirtualRoot(instance: VmInstance, enabled: Boolean) = Unit

    /**
     * 设置隐藏 root（反检测）开关（Android 虚拟化才有意义）。
     * 默认空实现：Linux / 客机 ROM 不处理。
     */
    suspend fun setHideRoot(instance: VmInstance, enabled: Boolean) = Unit

    /**
     * 设置隐藏 Xposed（反检测）开关（Android 虚拟化才有意义）。
     * 默认空实现：Linux / 客机 ROM 不处理。
     */
    suspend fun setHideXposed(instance: VmInstance, enabled: Boolean) = Unit
}
