package me.rerere.androidvm.engine

import android.util.Log
import me.rerere.androidvm.VirtualEngine
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmInstance
import me.rerere.androidvm.VmModuleInfo
import kotlin.math.abs

/**
 * 仿光速虚拟机的 Android 应用虚拟化引擎（黑盒 BlackBox 反射接入）。
 *
 * 全部通过反射调用 [me.rerere.androidvm.BlackBoxHost] 中核实的 BlackBoxCore API，
 * 不引入编译期依赖、不破坏宿主构建：未引入 Bcore 时调用抛明确异常（UI 据此提示未接入）。
 *
 * 多实例 / 多开映射：本模块的 [VmInstance.id] 经 [userIdOf] 映射为 BlackBox 的 userId
 * （每个实例 = 一个隔离用户空间）；虚拟 root / Magisk 由 BlackBox 的 XP 模块注入在运行时生效。
 */
class BlackBoxEngine : VirtualEngine {
    override val type = VmEngineType.ANDROID

    private val coreClass: Class<*>?
        get() = runCatching { Class.forName("top.niunaijun.blackbox.BlackBoxCore") }.getOrNull()

    private fun requireCore(): Pair<Class<*>, Any?> {
        val cls = coreClass ?: throw IllegalStateException("Android 虚拟化引擎尚未接入：请引入黑盒 BlackBox 的 Bcore 模块并完成初始化")
        val core = cls.getMethod("get").invoke(null)
        return cls to core
    }

    private fun userIdOf(instance: VmInstance): Int = abs(instance.id.hashCode())

    override suspend fun provision(
        instance: VmInstance,
        onProgress: (Float, String) -> Unit,
    ) {
        // BlackBox 不需要预置 ROM 镜像，应用按需安装到对应 userId 空间。
        onProgress(1f, "ready")
    }

    override suspend fun launch(instance: VmInstance, packageName: String?) {
        val pkg = packageName
            ?: throw IllegalArgumentException("Android 虚拟化需要指定要启动的包名")
        val (cls, core) = requireCore()
        val ok = cls.getMethod("launchApk", String::class.java, Int::class.java)
            .invoke(core, pkg, userIdOf(instance)) as? Boolean ?: false
        if (!ok) throw IllegalStateException("启动失败：$pkg（实例 ${instance.name}）")
    }

    override suspend fun installApp(instance: VmInstance, pathOrUrl: String): String {
        val (cls, core) = requireCore()
        cls.getMethod("installPackageAsUser", String::class.java, Int::class.java)
            .invoke(core, pathOrUrl, userIdOf(instance))
        return pathOrUrl
    }

    override suspend fun listApps(instance: VmInstance): List<String> {
        val (cls, core) = requireCore()
        @Suppress("UNCHECKED_CAST")
        val list = cls.getMethod("getInstalledPackages", Int::class.java, Int::class.java)
            .invoke(core, 0, userIdOf(instance)) as? List<Any> ?: return emptyList()
        return list.mapNotNull { pkg ->
            runCatching {
                val f = pkg.javaClass.getField("packageName")
                f.get(pkg) as? String
            }.getOrNull()
        }
    }

    override suspend fun installModule(instance: VmInstance, path: String): String {
        val (cls, core) = requireCore()
        val result = cls.getMethod("installXPModule", java.io.File::class.java)
            .invoke(core, java.io.File(path))
        val pkg = runCatching { result?.javaClass?.getField("packageName")?.get(result) as? String }.getOrNull()
        return pkg ?: path
    }

    override suspend fun listModules(): List<VmModuleInfo> {
        val (cls, core) = requireCore()
        @Suppress("UNCHECKED_CAST")
        val list = cls.getMethod("getInstalledXPModules")
            .invoke(core) as? List<Any> ?: return emptyList()
        return list.mapNotNull { m ->
            runCatching {
                val c = m.javaClass
                val pkg = c.getField("packageName").get(m) as? String ?: return@mapNotNull null
                val name = (c.getField("name").get(m) as? String) ?: pkg
                val enabled = (c.getField("enable").get(m) as? Boolean) ?: false
                VmModuleInfo(packageName = pkg, name = name, enabled = enabled)
            }.getOrNull()
        }
    }

    override suspend fun setModuleEnabled(packageName: String, enabled: Boolean) {
        val (cls, core) = requireCore()
        cls.getMethod("setModuleEnable", String::class.java, Boolean::class.java)
            .invoke(core, packageName, enabled)
    }

    override suspend fun uninstallModule(packageName: String) {
        val (cls, core) = requireCore()
        cls.getMethod("uninstallXPModule", String::class.java).invoke(core, packageName)
    }

    override suspend fun clone(instance: VmInstance, newName: String): VmInstance {
        // 新实例使用新 id（新 userId），形成独立隔离空间；真实数据克隆需复制用户空间目录，
        // 此处返回新的空实例，由用户在真机侧按需迁移数据。
        return instance.copy(
            id = abs(newName.hashCode()).toString(),
            name = newName,
            createdAt = System.currentTimeMillis(),
            installedApps = emptyList(),
        )
    }

    override suspend fun destroy(instance: VmInstance) {
        val (cls, core) = requireCore()
        val userId = userIdOf(instance)
        for (pkg in instance.installedApps) {
            runCatching {
                cls.getMethod("uninstallPackageAsUser", String::class.java, Int::class.java)
                    .invoke(core, pkg, userId)
            }.onFailure { Log.w("BlackBoxEngine", "卸载 $pkg 失败", it) }
        }
    }
}
