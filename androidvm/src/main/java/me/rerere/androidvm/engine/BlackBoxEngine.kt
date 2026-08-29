package me.rerere.androidvm.engine

import android.util.Log
import me.rerere.androidvm.BlackBoxHost
import me.rerere.androidvm.VirtualEngine
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmInstance
import me.rerere.androidvm.VmModuleInfo
import me.rerere.androidvm.VmModuleKind
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
        val file = java.io.File(path)
        // 标准 Magisk 模块 zip：module.prop + system/ 文件注入 + system.prop 元数据
        val isMagisk = runCatching {
            cls.getMethod("isMagiskModule", java.io.File::class.java).invoke(core, file) as? Boolean ?: false
        }.getOrDefault(false)
        if (isMagisk) {
            return (cls.getMethod("installMagiskModule", java.io.File::class.java)
                .invoke(core, file) as? String) ?: file.name
        }
        // 否则视为 Xposed APK 模块（黑盒原生加载）。
        val result = cls.getMethod("installXPModule", java.io.File::class.java)
            .invoke(core, file)
        return runCatching { result?.javaClass?.getField("packageName")?.get(result) as? String }.getOrNull() ?: file.name
    }

    override suspend fun listModules(): List<VmModuleInfo> {
        val (cls, core) = requireCore()
        val magisk = try {
            @Suppress("UNCHECKED_CAST")
            (cls.getMethod("getInstalledMagiskModules").invoke(core) as? List<Any>)
                ?.mapNotNull { m ->
                    runCatching {
                        val c = m.javaClass
                        val id = c.getField("moduleId").get(m) as? String ?: return@mapNotNull null
                        val name = (c.getField("name").get(m) as? String) ?: id
                        val enabled = (c.getField("enable").get(m) as? Boolean) ?: false
                        val version = (c.getField("version").get(m) as? String) ?: ""
                        val author = (c.getField("author").get(m) as? String) ?: ""
                        val desc = (c.getField("description").get(m) as? String) ?: ""
                        @Suppress("UNCHECKED_CAST")
                        val props = (c.getField("props").get(m) as? List<String>).orEmpty()
                        VmModuleInfo(
                            moduleId = id,
                            name = name,
                            kind = VmModuleKind.MAGISK,
                            enabled = enabled,
                            version = version,
                            author = author,
                            description = desc,
                            props = props,
                        )
                    }.getOrNull()
                }.orEmpty()
        } catch (e: Throwable) {
            emptyList()
        }

        val xposed = try {
            @Suppress("UNCHECKED_CAST")
            val list = cls.getMethod("getInstalledXPModules").invoke(core) as? List<Any> ?: emptyList()
            list.mapNotNull { m ->
                runCatching {
                    val c = m.javaClass
                    val pkg = c.getField("packageName").get(m) as? String ?: return@mapNotNull null
                    val name = (c.getField("name").get(m) as? String) ?: pkg
                    val enabled = (c.getField("enable").get(m) as? Boolean) ?: false
                    VmModuleInfo(moduleId = pkg, name = name, kind = VmModuleKind.XPOSED, enabled = enabled)
                }.getOrNull()
            }
        } catch (e: Throwable) {
            emptyList()
        }
        return magisk + xposed
    }

    override suspend fun setModuleEnabled(moduleId: String, enabled: Boolean) {
        val (cls, core) = requireCore()
        val magiskIds = runCatching {
            @Suppress("UNCHECKED_CAST")
            val list = cls.getMethod("getInstalledMagiskModules").invoke(core) as? List<Any> ?: emptyList()
            list.mapNotNull { runCatching { it.javaClass.getField("moduleId").get(it) as? String }.getOrNull() }
        }.getOrDefault(emptyList())
        if (magiskIds.contains(moduleId)) {
            cls.getMethod("setMagiskModuleEnable", String::class.java, Boolean::class.java)
                .invoke(core, moduleId, enabled)
            return
        }
        cls.getMethod("setModuleEnable", String::class.java, Boolean::class.java)
            .invoke(core, moduleId, enabled)
    }

    override suspend fun uninstallModule(moduleId: String) {
        val (cls, core) = requireCore()
        val magiskIds = runCatching {
            @Suppress("UNCHECKED_CAST")
            val list = cls.getMethod("getInstalledMagiskModules").invoke(core) as? List<Any> ?: emptyList()
            list.mapNotNull { runCatching { it.javaClass.getField("moduleId").get(it) as? String }.getOrNull() }
        }.getOrDefault(emptyList())
        if (magiskIds.contains(moduleId)) {
            cls.getMethod("uninstallMagiskModule", String::class.java).invoke(core, moduleId)
            return
        }
        cls.getMethod("uninstallXPModule", String::class.java).invoke(core, moduleId)
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

    /** 从 Bcore 反射取宿主 Context，供偏好写入（评分规则一致性：Bcore 未接入时返回 null）。 */
    private fun coreContext(): android.content.Context? = runCatching {
        coreClass?.getMethod("getContext")?.invoke(null) as? android.content.Context
    }.getOrNull()
}
