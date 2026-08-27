package me.rerere.androidvm

/**
 * 系统镜像目录（仿光速虚拟机 ROM 市场）。
 *
 * 光速虚拟机兼容 Android 7 / 10 / 12 三档系统；本目录额外保留 Linux 发行版作为
 * 可立即运行的容器模式（proot）。所有 URL 需保证可达。
 */
data class VmImage(
    val id: String,
    val engineType: VmEngineType,
    val systemLabel: String,
    val description: String,
    val rootfsUrl: String,
    /** 预估下载体积（MB），仅用于 UI 展示 */
    val sizeHintMb: Int,
)

object VmCatalog {
    val images: List<VmImage> = listOf(
        // —— Android 应用虚拟化（免 root / Hook 框架，需 FBlackBox 引擎接入）——
        VmImage(
            id = "android-7",
            engineType = VmEngineType.ANDROID,
            systemLabel = "Android 7.1",
            description = "兼容老旧的 32 位应用与游戏",
            rootfsUrl = "https://example.com/rom/android-7.tar.gz",
            sizeHintMb = 220,
        ),
        VmImage(
            id = "android-10",
            engineType = VmEngineType.ANDROID,
            systemLabel = "Android 10",
            description = "主流应用兼容性好，性能均衡",
            rootfsUrl = "https://example.com/rom/android-10.tar.gz",
            sizeHintMb = 260,
        ),
        VmImage(
            id = "android-12",
            engineType = VmEngineType.ANDROID,
            systemLabel = "Android 12",
            description = "最新特性，适合现代应用",
            rootfsUrl = "https://example.com/rom/android-12.tar.gz",
            sizeHintMb = 300,
        ),
        // —— 客机 ROM 容器（完整安卓用户态，可刷真 Magisk；需真机 + ARM ROM 镜像）——
        VmImage(
            id = "guest-android-12",
            engineType = VmEngineType.GUEST_ROM,
            systemLabel = "Android 12 ROM（客机）",
            description = "完整安卓用户态，可刷入 Magisk 获得真 root（路线 B，待真机验收）",
            rootfsUrl = "", // ROM 镜像地址由用户提供/构建（GB 级，不入库）
            sizeHintMb = 1200,
        ),
        // —— Linux 容器（proot，可立即运行）——
        VmImage(
            id = "ubuntu-24.04",
            engineType = VmEngineType.LINUX,
            systemLabel = "Ubuntu 24.04 LTS",
            description = "稳定长期支持，apt 软件生态完整",
            rootfsUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            sizeHintMb = 35,
        ),
        VmImage(
            id = "ubuntu-25.10",
            engineType = VmEngineType.LINUX,
            systemLabel = "Ubuntu 25.10",
            description = "最新发行版，软件包最新",
            rootfsUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/25.10/release/ubuntu-base-25.10-base-arm64.tar.gz",
            sizeHintMb = 36,
        ),
        VmImage(
            id = "alpine-3.22",
            engineType = VmEngineType.LINUX,
            systemLabel = "Alpine 3.22",
            description = "极轻量，仅约 3MB，启动快",
            rootfsUrl = "https://mirrors.aliyun.com/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.0-aarch64.tar.gz",
            sizeHintMb = 3,
        ),
    )

    fun default(): VmImage = images.first()
}
