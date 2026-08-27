package me.rerere.androidvm.navigation

/**
 * androidvm 模块内部的导航契约，与宿主 app 的 Navigator 解耦。
 *
 * 宿主（RouteActivity）负责将 [me.rerere.rikkahub.ui.context.Navigator] 适配为本接口，
 * 从而让 androidvm 页面无需反向依赖 app 模块。导航对象由宿主在调用页面时直接传入。
 */
interface VmNavigator {
    fun toDetail(instanceId: String)
    fun back()
}
