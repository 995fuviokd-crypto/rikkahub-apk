package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption

/**
 * AI 工具能力目录：集中描述 AI 在对话中可执行的全部工具。
 * 用于设置页"AI 工具清单"展示，帮助用户了解授予 AI 的完整能力范围。
 * 与运行时的工具注册（LocalTools/SystemControlTools 等）一一对应。
 */
data class ToolCapabilityEntry(
    val group: String,
    val name: String,
    val description: String,
    val option: LocalToolOption? = null,
)

object ToolCapabilityCatalog {
    val entries: List<ToolCapabilityEntry> = listOf(
        ToolCapabilityEntry("本地工具", "JavaScript", "在软件内 V8 引擎中执行 JavaScript 代码片段"),
        ToolCapabilityEntry("本地工具", "时间信息", "获取当前时间/日期/时区等时间信息", LocalToolOption.TimeInfo),
        ToolCapabilityEntry("本地工具", "剪贴板", "读取与写入系统剪贴板", LocalToolOption.Clipboard),
        ToolCapabilityEntry("本地工具", "文字转语音", "朗读文本（TTS）", LocalToolOption.Tts),
        ToolCapabilityEntry("本地工具", "询问用户", "向用户提问并等待回答", LocalToolOption.AskUser),
        ToolCapabilityEntry("本地工具", "屏幕使用时间", "查询屏幕使用时长统计", LocalToolOption.ScreenTime),
        ToolCapabilityEntry("本地工具", "日历", "创建与查询日历日程", LocalToolOption.Calendar),
        ToolCapabilityEntry("本地工具", "设备信息", "读取设备硬件/屏幕信息", LocalToolOption.DeviceInfo),
        ToolCapabilityEntry("本地工具", "无障碍服务", "通过无障碍服务执行系统操作（点击/滚动/输入）", LocalToolOption.Accessibility),
        ToolCapabilityEntry("本地工具", "电源管理", "电池信息与忽略电池优化", LocalToolOption.PowerManagement),
        ToolCapabilityEntry("本地工具", "ADB/Root", "通过无障碍模拟执行 ADB 与 shell 命令", LocalToolOption.Adb),
        ToolCapabilityEntry("本地工具", "脚本市场", "执行脚本市场脚本与 ToolPkg 插件导出的工具", LocalToolOption.Scripts),
        ToolCapabilityEntry("本地工具", "Termux 桥接", "在真实 Termux Linux 环境执行命令（需 Termux 应用与 allow-external-apps）", LocalToolOption.Termux),
        ToolCapabilityEntry("本地工具", "虚拟机控制", "枚举虚拟机实例，安装/启动/卸载实例内应用，管理 Xposed 模块（Android 引擎需 blackbox 构建）", LocalToolOption.Vm),
        ToolCapabilityEntry(
            "全能控制",
            "系统控制（全能权限）",
            "读写软件内全部设置与开关、切换模型、管理助手、启用/禁用插件、读写提示词",
            LocalToolOption.SystemControl,
        ),
        ToolCapabilityEntry("工作区", "工作区工具", "在沙箱工作区内读写文件、执行 shell 命令、安装运行项目"),
        ToolCapabilityEntry("工作流", "工作流工具", "触发与执行用户配置的多步工作流"),
        ToolCapabilityEntry("网络搜索", "搜索工具", "调用已配置的搜索服务联网检索信息"),
        ToolCapabilityEntry("技能", "技能工具", "调用已启用的内置技能完成专业任务"),
    )
}