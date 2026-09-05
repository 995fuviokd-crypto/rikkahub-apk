package me.rerere.androidvm.engine

/**
 * 客机 ROM 容器引擎的原生契约（JNI）。
 *
 * 真实逻辑在 jni/guestrom.c（需 NDK + 真机，沙盒不编译）。Kotlin 侧仅声明 external fun，
 * 运行时由 [init] 加载 libguestrom.so；未接入时调用抛 UnsatisfiedLinkError，主构建不受影响。
 *
 * 方法语义见 .monkeycode/specs/androidvm-guest-rom/design.md 第 3、5 节。
 */
object GuestRomNative {
    /** 原生库是否已加载（guestrom.native.enable 构建开关 + NDK 产物存在时为 true） */
    val available: Boolean = runCatching { System.loadLibrary("guestrom") }.isSuccess

    /** 准备客机根：解包 ROM tar、建 data/vendor overlay、放置 Magisk zip */
    external fun prepare(rootDir: String, romUrl: String)

    /** 启动客机用户态：mount namespace + chroot + 第二 servicemanager/zygote（客机 init 作为 PID1） */
    external fun boot(rootDir: String)

    /** 对客机 boot.img/initramfs 执行 Magisk boot_patch（magiskinit 替换 /init） */
    external fun patchBoot(rootDir: String, magiskZip: String)

    /** 重启客机用户态（重新执行被 patch 后的 init），不重启宿主 */
    external fun rebootGuest(rootDir: String)

    /** 停止并清理客机 */
    external fun destroy(rootDir: String)
}
