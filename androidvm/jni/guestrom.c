/*
 * guestrom.c —— 客机 ROM 容器引擎原生实现（路线 B）
 *
 * 编译：需 NDK，并在 androidvm/build.gradle.kts 启用
 *   externalNativeBuild { ndkBuild { path "src/main/jni/Android.mk" } }
 * 本文件默认不参与沙盒构建（无 NDK），仅作为真机实现的起点。
 *
 * 职责（详见 .monkeycode/specs/androidvm-guest-rom/design.md 第 3、5 节）：
 *   - prepare : 解包 redroid/Waydroid 的 arm64 rootfs 到 <rootDir>/system
 *   - boot    : 建 mount/pid/uts/ipc namespace → pivot_root+chroot → 以客机 /init 为 PID1 启动第二安卓用户态
 *   - patchBoot: 对客机 initramfs 执行 Magisk boot_patch（magiskinit 替换 /init）
 *   - rebootGuest: 重启客机用户态（重跑 patch 后 init），不重启宿主
 *   - destroy : 停止并清理
 *
 * 注意：宿主是 Android App，能否建 namespace / binder 上下文取决于宿主内核与 selinux 权限，
 *       生产需配套 privileged 原生 helper 或已 root 环境。
 */

#include <jni.h>
#include <sched.h>
#include <unistd.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>

static pid_t g_guest_pid = 0;

/* 在子进程（新 PID namespace 的 PID1）中挂载客机根并 exec 客机 init */
static int exec_guest_init(const char *root) {
    char base[4096];
    snprintf(base, sizeof(base), "%s/system", root);

    /* 私有挂载树，避免影响宿主 */
    mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL);

    /* 绑定客机 system 为根 */
    if (mount(base, base, NULL, MS_BIND | MS_REC, NULL) != 0) {
        return -1;
    }

    /* 建 pivot_root 所需的临时父目录 */
    char put_root[4096];
    snprintf(put_root, sizeof(put_root), "%s/.put_old", base);
    mkdir(put_root, 0700);

    if (pivot_root(base, put_root) != 0) {
        /* 回退：直接 chroot */
        if (chroot(base) != 0) return -2;
        chdir("/");
    } else {
        chdir("/");
        /* 卸载旧根 */
        mount("", "/.put_old", NULL, MS_PRIVATE | MS_REC, NULL);
        umount2("/.put_old", MNT_DETACH);
        rmdir("/.put_old");
    }

    /* TODO: 为客机建独立 binder 上下文（binderfs 新 context 或 vndbinder/hwbinder 复用），
     *       避免与宿主 servicemanager 冲突；并挂载 /dev、/proc、/sys、/data、/vendor overlay。 */

    execl("/init", "/init", (char *)NULL);
    /* 若客机无 /init（仅 system.img），改为启动第二 zygote：
       execl("/system/bin/app_process64", "app_process64", "/system/bin", "--zygote",
             "--start-system-server", (char *)NULL); */
    return -3;
}

static void java_throw(JNIEnv *env, const char *msg) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), msg);
}

JNIEXPORT void JNICALL
Java_me_rerere_androidvm_engine_GuestRomNative_prepare(JNIEnv *env, jobject thiz,
                                                      jstring rootDir, jstring romUrl) {
    const char *root = (*env)->GetStringUTFChars(env, rootDir, NULL);
    const char *url = (*env)->GetStringUTFChars(env, romUrl, NULL);
    char cmd[8192];
    /* romUrl 为空表示 ROM 已由外部预置；否则按本地 tar 路径解包 */
    if (url && url[0]) {
        snprintf(cmd, sizeof(cmd), "mkdir -p %s/system && tar xf %s -C %s/system", root, url, root);
        if (system(cmd) != 0) java_throw(env, "ROM 解包失败");
    } else {
        snprintf(cmd, sizeof(cmd), "mkdir -p %s/system %s/data %s/vendor", root, root, root);
        system(cmd);
    }
    (*env)->ReleaseStringUTFChars(env, rootDir, root);
    (*env)->ReleaseStringUTFChars(env, romUrl, url);
}

JNIEXPORT void JNICALL
Java_me_rerere_androidvm_engine_GuestRomNative_boot(JNIEnv *env, jobject thiz, jstring rootDir) {
    const char *root = (*env)->GetStringUTFChars(env, rootDir, NULL);

    /* 新 PID + mount + uts + ipc namespace；NET 视隔离需求另行加 CLONE_NEWNET */
    if (unshare(CLONE_NEWPID | CLONE_NEWNS | CLONE_NEWUTS | CLONE_NEWIPC) != 0) {
        (*env)->ReleaseStringUTFChars(env, rootDir, root);
        java_throw(env, "unshare 失败（宿主内核/selinux 可能未放行）");
        return;
    }

    pid_t pid = fork();
    if (pid == 0) {
        int rc = exec_guest_init(root);
        _exit(rc);
    } else if (pid < 0) {
        (*env)->ReleaseStringUTFChars(env, rootDir, root);
        java_throw(env, "fork 客机 init 失败");
        return;
    }

    g_guest_pid = pid; /* 父进程（宿主侧）记录客机 PID1，用于 reboot/destroy */
    (*env)->ReleaseStringUTFChars(env, rootDir, root);
}

JNIEXPORT void JNICALL
Java_me_rerere_androidvm_engine_GuestRomNative_patchBoot(JNIEnv *env, jobject thiz,
                                                         jstring rootDir, jstring magiskZip) {
    const char *root = (*env)->GetStringUTFChars(env, rootDir, NULL);
    const char *zip = (*env)->GetStringUTFChars(env, magiskZip, NULL);
    /* 客机 initramfs（容器模型下即客机 /init 所在 ramdisk）。真机需定位客机 boot.img/ramdisk 路径。 */
    char ramdisk[4096];
    snprintf(ramdisk, sizeof(ramdisk), "%s/system", root); /* 占位：实际应为客机 boot.img/ramdisk */
    char cmd[8192];
    /* 调用 Magisk 安装器内的 boot_patch.sh（magiskinit 替换 /init + libmagiskboot 重打包） */
    snprintf(cmd, sizeof(cmd),
             "cd %s && sh %s/assets/boot_patch.sh %s", root, zip, ramdisk);
    if (system(cmd) != 0) java_throw(env, "Magisk boot_patch 失败：客机 init/ramdisk 可能非标准");
    (*env)->ReleaseStringUTFChars(env, rootDir, root);
    (*env)->ReleaseStringUTFChars(env, magiskZip, zip);
}

JNIEXPORT void JNICALL
Java_me_rerere_androidvm_engine_GuestRomNative_rebootGuest(JNIEnv *env, jobject thiz, jstring rootDir) {
    const char *root = (*env)->GetStringUTFChars(env, rootDir, NULL);
    if (g_guest_pid > 0) {
        kill(g_guest_pid, SIGKILL); /* 停止客机用户态（不重启宿主） */
        waitpid(g_guest_pid, NULL, 0);
    }
    /* 重新启动（走 patch 后的 init） */
    Java_me_rerere_androidvm_engine_GuestRomNative_boot(env, thiz, rootDir);
    (*env)->ReleaseStringUTFChars(env, rootDir, root);
}

JNIEXPORT void JNICALL
Java_me_rerere_androidvm_engine_GuestRomNative_destroy(JNIEnv *env, jobject thiz, jstring rootDir) {
    const char *root = (*env)->GetStringUTFChars(env, rootDir, NULL);
    if (g_guest_pid > 0) { kill(g_guest_pid, SIGKILL); waitpid(g_guest_pid, NULL, 0); g_guest_pid = 0; }
    char cmd[4096];
    snprintf(cmd, sizeof(cmd), "rm -rf %s", root);
    system(cmd);
    (*env)->ReleaseStringUTFChars(env, rootDir, root);
}
