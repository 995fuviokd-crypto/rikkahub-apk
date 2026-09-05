package me.rerere.androidvm.engine

import android.content.Context
import me.rerere.androidvm.VirtualEngine
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmInstance
import me.rerere.androidvm.VmModuleInfo
import java.io.File

/**
 * 客机 ROM 容器引擎（路线 B）：在宿主内核上启动第二套安卓用户态，可刷真 Magisk。
 *
 * 本类只做编排，真实能力委托 [GuestRomNative]（JNI，jni/guestrom.c，需 NDK + 真机）。
 * 沙盒无 NDK/设备，方法运行时才会真正执行；主构建因 external fun 仅运行时链接而保持绿色。
 *
 * 完整设计见 .monkeycode/specs/androidvm-guest-rom（ROM 来源取 redroid arm64 / Waydroid arm64）。
 */
class GuestRomEngine(private val context: Context) : VirtualEngine {
    override val type = VmEngineType.GUEST_ROM

    private fun rootDir(instance: VmInstance): File =
        File(context.filesDir, "guestrom/${instance.id}")

    override suspend fun provision(instance: VmInstance, onProgress: (Float, String) -> Unit) {
        check(GuestRomNative.available) {
            "客机 ROM 引擎未接入：需在构建时启用 guestrom.native.enable 并提供 ROM 镜像（真机路线）"
        }
        check(instance.rootfsUrl.isNotBlank()) { "客机 ROM 镜像地址为空：请在实例中配置 ROM URL" }
        GuestRomNative.prepare(rootDir(instance).absolutePath, instance.rootfsUrl)
        onProgress(1f, "ready")
    }

    override suspend fun launch(instance: VmInstance, packageName: String?) {
        check(GuestRomNative.available) { "客机 ROM 引擎未接入" }
        GuestRomNative.boot(rootDir(instance).absolutePath)
    }

    override suspend fun installApp(instance: VmInstance, pathOrUrl: String): String {
        // 经客机 PackageManager 安装；真机由原生侧借客机 adb/pm 完成。沙盒未实现。
        throw UnsupportedOperationException("客机 ROM 安装 APK：需真机，经 GuestRomNative 调客机 pm")
    }

    override suspend fun listApps(instance: VmInstance): List<String> = emptyList()

    override suspend fun installModule(instance: VmInstance, path: String): String {
        // path 为 Magisk 安装器 zip；boot_patch 作用于该客机实例的 initramfs
        GuestRomNative.patchBoot(rootDir(instance).absolutePath, path)
        return "magisk"
    }

    override suspend fun listModules(): List<VmModuleInfo> = emptyList()
override suspend fun setModuleEnabled(moduleId: String, enabled: Boolean) = Unit

    override suspend fun uninstallModule(moduleId: String) = Unit
    override suspend fun clone(instance: VmInstance, newName: String): VmInstance =
        instance.copy(id = newName, name = newName, createdAt = System.currentTimeMillis())

    override suspend fun destroy(instance: VmInstance) {
        GuestRomNative.destroy(rootDir(instance).absolutePath)
    }

    override suspend fun rebootGuest(instance: VmInstance) {
        GuestRomNative.rebootGuest(rootDir(instance).absolutePath)
    }
}
