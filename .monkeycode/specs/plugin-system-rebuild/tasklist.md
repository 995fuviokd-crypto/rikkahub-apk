# 需求实施计划：插件系统深度重构（plugin-system-rebuild）

> 依据 design.md 两期交付。任务 1-5 为 Phase 1，任务 6-10 为 Phase 2。

- [x] 1. 崩溃根治：插件依赖解析安全化（D1.1）
  - [x] 1.1 WebViewPage 依赖懒加载改造
    - 修改 `ui/pages/webview/WebViewPage.kt`：三个 koinInject（PluginManager/ScriptRuntime/CordisPluginBridge）移出无条件组合路径
    - pluginId 为空时零插件依赖解析；非空时经 remember(pluginId) 包 runCatching，失败呈现"插件运行环境不可用"占位态（R1.1、R1.3）
  - [x] 1.2 CordisPluginBridge 重依赖惰性化
    - 修改 `di/RepositoryModule.kt:199-210`：ChatService、ConversationRepository、AgentHost 构造参数改为 Provider 惰性注入（R1.1）
  - [x] 1.3 CordisHostEventBus 副作用清理
    - 构造期常驻协程（CordisHostEventBus.kt:36-42）移到显式 start()，由 PluginSubsystemsScope 生命周期管理（R1.2）
  - [x] 1.4 PluginBoundary 异常边界
    - 新建 `data/plugin/PluginBoundary.kt`：包装插件子系统对外入口，runCatching + sealed PluginSubsystemError 结构化错误（R1.2）

- [x] 2. 能力缝真实接线（D1.2）
  - [x] 2.1 llm.infer 全参数透传
    - 修改 `data/plugin/CordisJsBridge.kt` llm.infer（98-112 行）：签名扩展 messages/model/systemPrompt 透传，删除恒空列表硬编码（R2.1）
  - [x] 2.2 tools 缝生产者与消费者
    - 新建 `data/plugin/ScriptToolsSeamProducer.kt`：把 ScriptRuntime 已启用 JS 插件工具注册进 HostToolsSeam 注册表；notifyChanged 改事件驱动发出 tools/change（R2.2）
  - [x] 2.3 sessions 缝绑定真实会话
    - HostSessionsSeam.bind 接入 ConversationRepository（Provider），append/rebuildContext 落真实会话表（R2.3）
  - [x] 2.4 未实现能力结构化标记
    - CordisKernel.seam() 返回 null 时 Bridge 返回 {"ok":false,"reason":"unimplemented"}；安装期用 HOST_CAPABILITIES 做预检标记（R2.4）
  - [x] 2.5 能力接线单元测试
    - tools/change 事件发出与消费、seam 未声明/未实现两种错误路径、llm.infer 参数透传

- [x] 3. 检查点 - 确保所有测试通过,如有疑问请询问用户
  - 检查 1-2 全部子任务完成且单测通过（无构建环境下以静态检查 + 可运行单测为准）

- [x] 4. 面板运行现代化（D1.3）
  - [x] 4.1 异步调用协议
    - CordisJsBridge 新增 seamCallAsync(seam, method, argsJson)：返回 callId，宿主 IO 协程执行后 evaluateJavascript 回推 CordisBridge.onResult(callId, json)；旧 seamCall 保留为同步兼容通道（R3.1）
  - [x] 4.2 事件推送模型
    - CordisHostEventBus 新增 subscribe(pluginId, handler)：JS 侧 CordisEvents.subscribe(topics)，宿主 evaluateJavascript 主动推送；环形缓冲拉取保留为断线恢复（R3.2）
  - [x] 4.3 执行调度与协程安全
    - CordisJsExecutor.invoke 切 Dispatchers.Default；kernel currentPluginId/currentCapabilities 由 @Volatile 改协程局部（withContext + coroutineContext element），移除 runBlocking 依赖；dispose 内嵌 runBlocking 全部移除改结构化取消（R3.3、R6.1）
  - [x] 4.4 异步桥协议单元测试
    - callId 配对、乱序回推、订阅去重、取消时监听器清理

- [x] 5. 检查点 - 确保所有测试通过,如有疑问请询问用户
  - Phase 1 完成；向用户提请本机构建验证（遵守"RikkaHub 暂不构建"约束，由用户决定验证时机）

- [x] 6. 插件中心 PluginHubPage（D2.1）
  - [x] 6.1 插件中心卡片流
    - 新建 `ui/pages/hub/PluginHubPage.kt`：已安装插件卡片（图标/名称/版本/能力徽章/运行状态/快捷开关），运行状态读 kernel pluginsState（R4.1）
  - [x] 6.2 插件详情页
    - 功能说明、配置表单（复用 PluginConfigDialog schema 渲染）、权限（能力缝）清单、工具/面板/提示词入口、卸载/更新（R4.2）
  - [x] 6.3 入口与一键打开
    - 抽屉（ChatDrawer.kt:559 区域）与设置页 PluginExtensionsCard 指向插件中心；卡片"打开面板"直接路由面板宿主（R4.3、R4.5）

- [x] 7. 双轨面板宿主（D2.2）
  - [x] 7.1 manifest panel 扩展
    - PluginInfo/PluginDeclaration 增加 panel.type(schema|web)/panel.entry/panel.script 字段，缺省 web 向后兼容（Decision 3）
  - [x] 7.2 Schema 渲染器与组件目录
    - 新建 `ui/schema/`：SchemaPanelRenderer（Compose）+ 类型化组件目录 Card/Text/Button/Toggle/Slider/Select/List/Grid/Section/Chart/Progress/Markdown，未知组件安全降级占位（R4.2）
  - [x] 7.3 Schema 交互回传
    - 组件事件 → seamCallAsync('ui','onAction',...) → 插件 JS 逻辑 → 新 schema 增量重渲染（key diff）（R3.1）
  - [x] 7.4 Web 轨设计体系注入
    - WebView 加载前注入 Material3 dynamicColorScheme CSS 变量 + cordis.css 轻量组件样式表（R4.2）
  - [x] 7.5 Schema 渲染器单元测试
    - 未知组件降级、props 类型校验、key diff 增量

- [x] 8. 市场信任模型（D2.3）
  - [x] 8.1 安装确认页
    - BottomSheet：能力缝逐项披露（未实现项标灰）、用途、来源、版本与宿主兼容性（R7.1、R7.3）
  - [x] 8.2 安装状态机与回滚
    - Installing → Installed | Failed(原因) 状态流转 UI；失败自动回滚（复用 installZip 备份回滚并补 UI 呈现）（R7.2）
  - [x] 8.3 能力预检
    - 安装前 HOST_CAPABILITIES + schema 必填校验，"已证明可装"才亮安装按钮（R7.4、联动 2.4）

- [x] 9. 双管线统一（D2.4）
  - [x] 9.1 kernel 快照接口
    - CordisKernel 提供 snapshot() 冷数据接口（启用插件/工具/系统提示/Hook 清单），ChatService 免运行时耦合（R5.1）
  - [x] 9.2 ChatService 读取路径切换
    - enabledSystemPrompts/dispatchHook/LocalTools 读取改走 kernel snapshot()（R5.1、R5.2）
  - [x] 9.3 工具拆出元工具
    - 插件工具以 pluginId.toolName 直出工具列表，聊天工具调用块显示插件名与工具名；旧三段式调用协议保留兼容解析（R4.4、R5.1）
  - [x] 9.4 管线一致性测试
    - snapshot 与目录/DataStore 对账一致、禁用插件后工具即时移除（R6.2）

- [x] 10. 检查点 - 确保所有测试通过,如有疑问请询问用户
  - Phase 2 完成；整理交付摘要（改动文件清单、兼容性说明、遗留风险）
