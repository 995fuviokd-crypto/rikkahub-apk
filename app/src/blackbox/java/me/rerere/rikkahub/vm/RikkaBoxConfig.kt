package me.rerere.rikkahub.vm

import android.content.Context
import android.content.SharedPreferences
import top.niunaijun.blackbox.client.ClientConfiguration

/**
 * 仿光速虚拟机（黑盒 BlackBox）的 ClientConfiguration 实现。
 *
 * 本文件位于受开关控制的源集 app/src/blackbox：仅当 gradle.properties 设置
 * blackbox.enable=true（或 -Pblackbox.enable=true）时才参与编译——此时 :Bcore
 * 依赖已加入，ClientConfiguration 可解析。关闭开关时本文件不进入编译，主工程无 Bcore 依赖。
 *
 * BlackBoxCore.doAttachBaseContext 需要一个 ClientConfiguration 的具体子类；
 * RikkaHubApp.attachBaseContext 经由 BlackBoxHost 反射加载本类完成初始化。
 *
 * 虚拟 root / 反检测配置通过 SharedPreferences 与宿主（androidvm 的 VmVM）共享，
 * 保证宿主进程与虚拟 app 进程读到一致的开关状态。
 */
class RikkaBoxConfig(private val context: Context) : ClientConfiguration() {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)
    }

    override fun getHostPackageName(): String = context.packageName

    override fun isHideRoot(): Boolean = prefs.getBoolean(KEY_HIDE_ROOT, false)

    override fun isHideXposed(): Boolean = prefs.getBoolean(KEY_HIDE_XPOSED, false)

    override fun isVirtualRootEnabled(): Boolean = prefs.getBoolean(KEY_VIRTUAL_ROOT, false)

    companion object {
        const val PREFS_NAME = "rikkahub_vm"
        const val KEY_VIRTUAL_ROOT = "virtual_root"
        const val KEY_HIDE_ROOT = "hide_root"
        const val KEY_HIDE_XPOSED = "hide_xposed"
    }
}
