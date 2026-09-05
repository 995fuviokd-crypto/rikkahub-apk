package me.rerere.androidvm.engine

import android.util.Log
import me.rerere.androidvm.BlackBoxHost
import me.rerere.androidvm.VirtualEngine
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmInstance
import me.rerere.androidvm.VmModuleInfo
import me.rerere.androidvm.VmModuleKind
import java.io.File
import kotlin.math.abs

/**
 * 仿光速虚拟机的 Android 应用虚拟化引擎（黑盒 BlackBox 反射接入）。
 *
 * 全部通过反射调用 [me.rerere.androidvm.BlackBoxHost] 中核实的 BlackBoxCore API,
 * 不引入编译期依赖、不破坏宿主构建: 未引入 Bcore 时调用抛明确异常(UI 据此提示未接入)。
 *
 * 多实例 / 多开映射: 本模块的 [VmInstance.id] 经 [userIdOf] 映射为 BlackBox 的 userId
 * (每个实例 = 一个隔离用户空间; server 侧安装时自动 createUser, 幂等)。
 *
 * 方法签名对照 BlackBoxCore.java(compileSdk35 分支)核实:
 * - InstallResult installPackageAsUser(File apk, int userId)  ← APK 文件安装
 * - InstallResult installXPModule(File apk) / boolean isXposedModule(File)
 * - List<InstalledModule> getInstalledXPModules()  (字段: packageName/name/desc/enable)
 * - void setModuleEnable(String, boolean) / void uninstallXPModule(String)
 * - List<PackageInfo> getInstalledPackages(int flags, int userId)
 * - boolean launchApk(String packageName, int userId)
 * - void uninstallPackageAsUser(String packageName, int userId)
 */
class BlackBoxEngine : VirtualEngine {
    override val type = VmEngineType.ANDROID

    private val coreClass: Class<*>?
        get() = runCatching { Class.forName("top.niunaijun.blackbox.BlackBoxCore") }.getOrNull()

    private fun requireCore(): Pair<Class<*>, Any?> {
        val cls = coreClass ?: throw IllegalStateException("Android 虚拟化引擎尚未接入：请以 blackbox.enable=true 构建并引入 Bcore 模块")
        val core = cls.getMethod("get").invoke(null)
        return cls to core
    }

    internal fun isAvailable(): Boolean = coreClass != null

    /** 实例 id → BlackBox userId; abs(hash) 非负, 规避 Int.MIN_VALUE 取绝对值仍为负的坑 */
    internal fun userIdOf(instance: VmInstance): Int {
        val h = instance.id.hashCode()
        return if (h == Int.MIN_VALUE) 0 else abs(h)
    }

    override suspend fun provision(
        instance: VmInstance,
        onProgress: (Float, String) -> Unit,
    ) {
        // BlackBox 不需要预置 ROM 镜像, 应用按需安装到对应 userId 空间;
        // 引擎未接入时立即失败, 避免实例"假装就绪"到启动时才暴露
        requireCore()
        onProgress(1f, "ready")
    }

    override suspend fun launch(instance: VmInstance, packageName: String?) {
        val pkg = packageName ?: throw IllegalArgumentException("Android 虚拟化需要指定要启动的包名")
        val (cls, core) = requireCore()
        val ok = cls.getMethod("launchApk", String::class.java, Int::class.javaPrimitiveType)
            .invoke(core, pkg, userIdOf(instance)) as? Boolean ?: false
        if (!ok) throw IllegalStateException("启动失败：$pkg（实例 ${instance.name}，可能未安装或无可启动入口）")
    }

    /**
     * 向实例安装 APK。支持:
     * - 本地 APK 绝对路径
     * - 宿主已安装应用的包名(整包克隆进虚拟空间)
     * 返回安装成功的包名。
     */
    override suspend fun installApp(instance: VmInstance, pathOrPackageName: String): String {
        val (cls, core) = requireCore()
        val userId = userIdOf(instance)
        val result: Any? = if (File(pathOrPackageName).isFile) {
            cls.getMethod(
                "installPackageAsUser",
                File::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(core, File(pathOrPackageName), userId)
        } else {
            cls.getMethod(
                "installPackageAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(core, pathOrPackageName, userId)
        }
        val success = result?.javaClass?.getField("success")?.get(result) as? Boolean ?: false
        if (!success) {
            val msg = result?.javaClass?.getField("msg")?.get(result) as? String
            throw IllegalStateException("安装失败：${msg ?: "未知错误"}")
        }
        return result?.javaClass?.getField("packageName")?.get(result) as? String
            ?: pathOrPackageName
    }

    override suspend fun listApps(instance: VmInstance): List<String> {
        val (cls, core) = runCatching { requireCore() }.getOrNull() ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val list = cls.getMethod(
            "getInstalledPackages",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(core, 0, userIdOf(instance)) as? List<Any> ?: return emptyList()
        return list.mapNotNull { pkg ->
            runCatching {
                pkg.javaClass.getField("packageName").get(pkg) as? String
            }.getOrNull()
        }
    }

    /** 卸载实例内应用: 从该实例的 userId 空间移除(不影响宿主与其他实例)。 */
    override suspend fun uninstallApp(instance: VmInstance, packageName: String) {
        val (cls, core) = requireCore()
        cls.getMethod(
            "uninstallPackageAsUser",
            String::class.java,
            Int::class.javaPrimitiveType,
        ).invoke(core, packageName, userIdOf(instance))
    }

    // ===== Xposed 模块(BlackBox 内核唯一的模块体系; 无 Magisk API) =====

    override suspend fun installModule(instance: VmInstance, path: String): String {
        val (cls, core) = requireCore()
        val file = File(path)
        val isXposed = cls.getMethod("isXposedModule", File::class.java)
            .invoke(core, file) as? Boolean ?: false
        if (!isXposed) {
            throw IllegalArgumentException("不是有效的 Xposed 模块 APK：${file.name}")
        }
        val result = cls.getMethod("installXPModule", File::class.java).invoke(core, file)
        val success = result?.javaClass?.getField("success")?.get(result) as? Boolean ?: false
        if (!success) {
            val msg = result?.javaClass?.getField("msg")?.get(result) as? String
            throw IllegalStateException("模块安装失败：${msg ?: "未知错误"}")
        }
        return result?.javaClass?.getField("packageName")?.get(result) as? String ?: file.name
    }

    override suspend fun listModules(): List<VmModuleInfo> {
        val (cls, core) = runCatching { requireCore() }.getOrNull() ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val list = runCatching {
            cls.getMethod("getInstalledXPModules").invoke(core) as? List<Any>
        }.getOrNull() ?: return emptyList()
        return list.mapNotNull { m ->
            runCatching {
                val c = m.javaClass
                val pkg = c.getField("packageName").get(m) as? String ?: return@mapNotNull null
                VmModuleInfo(
                    moduleId = pkg,
                    name = (c.getField("name").get(m) as? String).orEmpty().ifBlank { pkg },
                    kind = VmModuleKind.XPOSED,
                    enabled = (c.getField("enable").get(m) as? Boolean) ?: false,
                    description = (c.getField("desc").get(m) as? String).orEmpty(),
                )
            }.getOrNull()
        }
    }

    override suspend fun setModuleEnabled(moduleId: String, enabled: Boolean) {
        val (cls, core) = requireCore()
        cls.getMethod("setModuleEnable", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(core, moduleId, enabled)
    }

    override suspend fun uninstallModule(moduleId: String) {
        val (cls, core) = requireCore()
        cls.getMethod("uninstallXPModule", String::class.java).invoke(core, moduleId)
    }

    /** 查询模块启用状态（非接口方法，供宿主详情页直接查询） */
    suspend fun isModuleEnabled(moduleId: String): Boolean {
        val (cls, core) = runCatching { requireCore() }.getOrNull() ?: return false
        return runCatching {
            cls.getMethod("isModuleEnable", String::class.java).invoke(core, moduleId) as? Boolean ?: false
        }.getOrDefault(false)
    }

    /** Xposed 框架总开关（非接口方法，模块生效的前提） */
    suspend fun setXPEnabled(enabled: Boolean) {
        val (cls, core) = requireCore()
        cls.getMethod("setXPEnable", Boolean::class.javaPrimitiveType).invoke(core, enabled)
    }

    override suspend fun clone(instance: VmInstance, newName: String): VmInstance {
        // 新实例使用新 id(新 userId), 形成独立隔离空间; 真实数据克隆需复制用户空间目录,
        // 此处返回新的空实例, 由用户在真机侧按需迁移数据。
        return instance.copy(
            id = abs(newName.hashCode()).toString(),
            name = newName,
            createdAt = System.currentTimeMillis(),
            installedApps = emptyList(),
        )
    }

    override suspend fun destroy(instance: VmInstance) {
        val (cls, core) = runCatching { requireCore() }.getOrNull() ?: return
        val userId = userIdOf(instance)
        for (pkg in instance.installedApps) {
            runCatching {
                cls.getMethod(
                    "uninstallPackageAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(core, pkg, userId)
            }.onFailure { Log.w("BlackBoxEngine", "卸载 $pkg 失败", it) }
        }
    }

    override suspend fun setVirtualRoot(instance: VmInstance, enabled: Boolean) {
        val ctx = coreContext() ?: return
        BlackBoxHost.setVirtualRoot(ctx, enabled)
    }

    override suspend fun setHideRoot(instance: VmInstance, enabled: Boolean) {
        val ctx = coreContext() ?: return
        BlackBoxHost.setHideRoot(ctx, enabled)
    }

    override suspend fun setHideXposed(instance: VmInstance, enabled: Boolean) {
        val ctx = coreContext() ?: return
        BlackBoxHost.setHideXposed(ctx, enabled)
    }

    /** 从 Bcore 反射取宿主 Context, 供偏好写入(Bcore 未接入时返回 null) */
    private fun coreContext(): android.content.Context? = runCatching {
        coreClass?.getMethod("getContext")?.invoke(null) as? android.content.Context
    }.getOrNull()
}
