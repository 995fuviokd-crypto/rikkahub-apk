package me.rerere.androidvm

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.androidvm.engine.BlackBoxEngine
import me.rerere.androidvm.engine.GuestRomEngine
import me.rerere.androidvm.engine.LinuxContainerEngine
import me.rerere.androidvm.navigation.VmNavigator
import me.rerere.androidvm.R
import java.util.UUID

/**
 * androidvm 页面的状态持有者（非 Android ViewModel，由 Composable 通过 remember 持有）。
 *
 * 负责实例清单的加载/持久化、引擎分派与安装进度广播。
 */
class VmVM(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val repo = VmRepository(context)

    val instances = mutableStateListOf<VmInstance>()

    /** 安装进度（0..1）。key = instanceId */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress = _progress.asStateFlow()

    /** 当前正在安装的实例 id */
    val installingId = mutableStateOf<String?>(null)

    /** 操作结果提示（启动/安装成功或失败），UI 以 Snackbar 展示 */
    val message = mutableStateOf<String?>(null)

    fun load() {
        scope.launch(Dispatchers.IO) {
            val loaded = repo.load()
            instances.clear()
            instances.addAll(loaded)
        }
    }

    private fun engineFor(instance: VmInstance): VirtualEngine =
        when (instance.engineType) {
            VmEngineType.LINUX -> LinuxContainerEngine(context)
            VmEngineType.ANDROID -> BlackBoxEngine()
            VmEngineType.GUEST_ROM -> GuestRomEngine(context)
        }

    fun createFromImage(image: VmImage, name: String) {
        val instance = VmInstance(
            id = UUID.randomUUID().toString().take(8),
            name = name.ifBlank { image.systemLabel },
            engineType = image.engineType,
            systemLabel = image.systemLabel,
            rootfsUrl = image.rootfsUrl,
        )
        instances.add(instance)
        persist()
        provision(instance)
    }

    fun provision(instance: VmInstance) {
        installingId.value = instance.id
        scope.launch(Dispatchers.IO) {
            try {
                engineFor(instance).provision(instance) { ratio, _ ->
                    _progress.value = _progress.value + (instance.id to ratio)
                }
                _progress.value = _progress.value + (instance.id to 1f)
            } catch (e: Throwable) {
                // 安装失败保留实例，UI 可重试；同时把原因透传给用户
                e.printStackTrace()
                message.value = context.getString(R.string.vm_msg_provision_failed, instance.name, e.message ?: e.javaClass.simpleName)
            } finally {
                installingId.value = null
            }
        }
    }

    fun delete(instance: VmInstance) {
        scope.launch(Dispatchers.IO) {
            runCatching { engineFor(instance).destroy(instance) }
        }
        instances.removeAll { it.id == instance.id }
        _progress.value = _progress.value - instance.id
        persist()
    }

    /** 启动实例内的某个应用（Android 模式需指定包名；Linux 模式确保 rootfs 就绪）。 */
    fun launch(instance: VmInstance, packageName: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { engineFor(instance).launch(instance, packageName) }
                .onFailure { message.value = context.getString(R.string.vm_msg_launch_failed, it.message) }
        }
    }

    /** 向实例安装 APK（Android 模式）。filePath 为本地 APK 路径。 */
    fun installApp(instance: VmInstance, filePath: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                engineFor(instance).installApp(instance, filePath)
                val apps = engineFor(instance).listApps(instance)
                val idx = instances.indexOfFirst { it.id == instance.id }
                if (idx >= 0) instances[idx] = instances[idx].copy(installedApps = apps)
                persist()
                message.value = context.getString(R.string.vm_msg_install_done)
            }.onFailure { message.value = context.getString(R.string.vm_msg_install_failed, it.message) }
        }
    }

    fun update(updated: VmInstance) {
        val idx = instances.indexOfFirst { it.id == updated.id }
        if (idx >= 0) instances[idx] = updated
        persist()
    }

    fun toggleVirtualRoot(instance: VmInstance, enabled: Boolean) {
        update(instance.copy(virtualRoot = enabled))
        scope.launch(Dispatchers.IO) {
            runCatching { engineFor(instance).setVirtualRoot(instance, enabled) }
                .onFailure { message.value = context.getString(R.string.vm_msg_set_failed, it.message) }
        }
    }

    fun toggleHideRoot(instance: VmInstance, enabled: Boolean) {
        update(instance.copy(hideRoot = enabled))
        scope.launch(Dispatchers.IO) {
            runCatching { engineFor(instance).setHideRoot(instance, enabled) }
                .onFailure { message.value = context.getString(R.string.vm_msg_set_failed, it.message) }
        }
    }

    fun toggleHideXposed(instance: VmInstance, enabled: Boolean) {
        update(instance.copy(hideXposed = enabled))
        scope.launch(Dispatchers.IO) {
            runCatching { engineFor(instance).setHideXposed(instance, enabled) }
                .onFailure { message.value = context.getString(R.string.vm_msg_set_failed, it.message) }
        }
    }

    fun toggleFloatingWindow(instance: VmInstance, enabled: Boolean) =
        update(instance.copy(floatingWindow = enabled))

    fun toggleKeepAlive(instance: VmInstance, enabled: Boolean) =
        update(instance.copy(keepAlive = enabled))

    // ===== 虚拟框架模块（Xposed/Magisk 模块，Bcore 内全局生效）=====
    val modules = mutableStateListOf<VmModuleInfo>()

    fun loadModules() {
        scope.launch(Dispatchers.IO) {
            runCatching { modules.clear(); modules.addAll(BlackBoxEngine().listModules()) }
                .onFailure { message.value = context.getString(R.string.vm_msg_load_modules_failed, it.message) }
        }
    }

    fun installModule(instance: VmInstance, filePath: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val engine = engineFor(instance)
                val pkg = engine.installModule(instance, filePath)
                modules.clear()
                modules.addAll(engine.listModules())
                message.value = context.getString(R.string.vm_msg_module_flashed, pkg)
            }.onFailure { message.value = context.getString(R.string.vm_msg_flash_failed, it.message) }
        }
    }

    fun setModuleEnabled(moduleId: String, enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                BlackBoxEngine().setModuleEnabled(moduleId, enabled)
                modules.clear()
                modules.addAll(BlackBoxEngine().listModules())
                message.value = if (enabled) {
                    context.getString(R.string.vm_msg_module_enabled, moduleId)
                } else {
                    context.getString(R.string.vm_msg_module_disabled, moduleId)
                }
            }.onFailure { message.value = context.getString(R.string.vm_msg_set_failed, it.message) }
        }
    }

    fun uninstallModule(moduleId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                BlackBoxEngine().uninstallModule(moduleId)
                modules.clear()
                modules.addAll(BlackBoxEngine().listModules())
                message.value = context.getString(R.string.vm_msg_module_uninstalled, moduleId)
            }.onFailure { message.value = context.getString(R.string.vm_msg_uninstall_failed, it.message) }
        }
    }

    /** 重启虚拟空间。客机 ROM 路线会真杀客机 PID1 重新 boot；其余路线下次启动生效。 */
    fun restart(instance: VmInstance) {
        if (instance.engineType == VmEngineType.GUEST_ROM) {
            scope.launch(Dispatchers.IO) {
                runCatching { engineFor(instance).rebootGuest(instance) }
                    .onSuccess { message.value = context.getString(R.string.vm_msg_guest_rebooted) }
                    .onFailure { message.value = context.getString(R.string.vm_msg_guest_reboot_failed, it.message) }
            }
        } else {
            message.value = context.getString(R.string.vm_msg_space_restarted)
        }
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) { repo.save(instances.toList()) }
    }
}
