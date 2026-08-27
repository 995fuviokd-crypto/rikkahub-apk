package me.rerere.rikkahub.vm

import android.content.Context
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
 */
class RikkaBoxConfig(private val context: Context) : ClientConfiguration() {
    override fun getHostPackageName(): String = context.packageName
}
