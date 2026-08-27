package me.rerere.androidvm

import kotlinx.serialization.Serializable

/**
 * 虚拟机引擎类型。
 *
 * - [LINUX]: 基于 proot 的用户态 Linux 容器，可立即运行（复用 workspace 模块）。
 * - [ANDROID]: 仿光速虚拟机的 Android 应用虚拟化（免 root、Hook 框架）。
 *   完整能力需要接入 FBlackBox 引擎（submodule + 宿主 Application 继承 VirtualInitializer + 真机联调），
 *   当前以契约桩提供统一的虚拟化接口，详见 [BlackBoxEngine]。
 */
enum class VmEngineType {
    LINUX,
    ANDROID,
    /** 客机 ROM 容器（完整安卓用户态，可刷真 Magisk）。见 .monkeycode/specs/androidvm-guest-rom */
    GUEST_ROM,
}

/**
 * 单个虚拟机实例的持久化描述。
 *
 * 仿光速虚拟机：每个实例是一套独立、隔离的运行环境，拥有自己的文件系统视图、
 * 应用安装列表、虚拟 root / Magisk 开关、悬浮窗与息屏保活配置。
 */
@Serializable
data class VmInstance(
    val id: String,
    val name: String,
    val engineType: VmEngineType,
    /** 系统版本展示名，如 "Android 12"、"Ubuntu 24.04" */
    val systemLabel: String,
    /** rootfs 下载地址（Linux 模式为 tar.gz；Android 模式为 ROM 镜像地址） */
    val rootfsUrl: String,
    val virtualRoot: Boolean = false,
    val floatingWindow: Boolean = false,
    val keepAlive: Boolean = false,
    /** 已安装应用包名或 Linux 软件名列表 */
    val installedApps: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 虚拟框架模块信息（Xposed/Magisk 模块在 Bcore 虚拟空间内加载）。
 *
 * 注意：用户态虚拟化无法运行真 Magisk（需修改 boot 镜像）；FBlackBox 的「Magisk 能力」
 * 实为在虚拟空间内加载 Xposed/模块，由 [me.rerere.androidvm.engine.BlackBoxEngine] 反射驱动。
 */
@Serializable
data class VmModuleInfo(
    val packageName: String,
    val name: String = "",
    val enabled: Boolean = false,
)
