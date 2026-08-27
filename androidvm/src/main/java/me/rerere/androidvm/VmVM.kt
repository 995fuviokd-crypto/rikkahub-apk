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
                // 安装失败保留实例，UI 可重试
                e.printStackTrace()
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
                .onFailure { message.value = "启动失败：${it.message}" }
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
                message.value = "安装完成"
            }.onFailure { message.value = "安装失败：${it.message}" }
        }
    }

    fun update(updated: VmInstance) {
        val idx = instances.indexOfFirst { it.id == updated.id }
        if (idx >= 0) instances[idx] = updated
        persist()
    }

    fun toggleVirtualRoot(instance: VmInstance, enabled: Boolean) =
        update(instance.copy(virtualRoot = enabled))

    fun toggleFloatingWindow(instance: VmInstance, enabled: Boolean) =
        update(instance.copy(floatingWindow = enabled))

    fun toggleKeepAlive(instance: VmInstance, enabled: Boolean) =
        update(instance.copy(keepAlive = enabled))

    // ===== 虚拟框架模块（Xposed/Magisk 模块，Bcore 内全局生效）=====
    val modules = mutableStateListOf<VmModuleInfo>()

    fun loadModules() {
        scope.launch(Dispatchers.IO) {
            runCatching { modules.clear(); modules.addAll(BlackBoxEngine().listModules()) }
                .onFailure { message.value = "读取模块失败：${it.message}" }
        }
    }

    fun installModule(instance: VmInstance, filePath: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val engine = engineFor(instance)
                val pkg = engine.installModule(instance, filePath)
                modules.clear()
                modules.addAll(engine.listModules())
                message.value = "Magisk/模块刷入完成：$pkg"
            }.onFailure { message.value = "刷入失败：${it.message}" }
        }
    }

    fun setModuleEnabled(packageName: String, enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                BlackBoxEngine().setModuleEnabled(packageName, enabled)
                modules.clear()
                modules.addAll(BlackBoxEngine().listModules())
                message.value = if (enabled) "已启用：$packageName" else "已停用：$packageName"
            }.onFailure { message.value = "设置失败：${it.message}" }
        }
    }

    fun uninstallModule(packageName: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                BlackBoxEngine().uninstallModule(packageName)
                modules.clear()
                modules.addAll(BlackBoxEngine().listModules())
                message.value = "已卸载：$packageName"
            }.onFailure { message.value = "卸载失败：${it.message}" }
        }
    }

    /** 重启虚拟空间。客机 ROM 路线会真杀客机 PID1 重新 boot；其余路线下次启动生效。 */
    fun restart(instance: VmInstance) {
        if (instance.engineType == VmEngineType.GUEST_ROM) {
            scope.launch(Dispatchers.IO) {
                runCatching { engineFor(instance).rebootGuest(instance) }
                    .onSuccess { message.value = "客机已重启，Magisk/模块已生效" }
                    .onFailure { message.value = "重启客机失败：${it.message}" }
            }
        } else {
            message.value = "虚拟空间已重启，Magisk/模块将在下次启动应用时生效"
        }
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) { repo.save(instances.toList()) }
    }
}
