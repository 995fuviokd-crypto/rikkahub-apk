# Requirements Document: RikkaHub 插件系统深度重构（plugin-system-rebuild）

## Introduction

将现有 Cordis 插件系统重构为与 RikkaHub 深度绑定的专业化插件体系：插件运行原理、运行方式与宿主深度集成，插件在 UI 中可视化、实用、便捷。以最终用户体验为基准，同时消除当前 WebViewPage 的崩溃链路。

## Glossary

- **宿主 (Host)**: RikkaHub Android 应用本体
- **插件 (Plugin)**: 经 zip 安装到 `filesDir/plugins/<id>/` 的扩展包（plugin.json + web 面板 + JS 脚本）
- **能力缝 (Seam)**: 宿主暴露给插件的能力接口（llm / tools / sessions / fs 等）
- **面板插件 (PANEL)**: 带 WebView UI 的插件
- **QuickJS**: 当前的 JS 执行引擎
- **插件市场 (Market)**: 官方 GitHub 市场仓库（默认 995fuviokd-crypto/plugin-market）+ 社区源

## 现状关键问题（调研依据：代码勘探报告）

| # | 问题 | 影响 |
|---|------|------|
| P1 | WebViewPage 组合期无条件 koinInject CordisPluginBridge，主线程拉起 ChatService 全依赖图（23 参数巨型链） | 崩溃/ANR 的直接根因 |
| P2 | Cordis 内核 9 个能力缝中 fs/sandbox/subprocess/shell/terminal/approval 是空壳，tools/sessions/systemPrompt 无生产者无消费者 | 插件能做的事极其有限 |
| P3 | CordisJsBridge 全部 runBlocking 同步：agent.run 冻结面板到生成结束；events 是拉模型 | 面板体验差 |
| P4 | 面板入口靠 plugin.json extensionPoints 兜底，无统一插件中心页；JS 工具藏在 run_script_tool 元工具背后需手动开启 | 插件"不可见、不实用、不便捷" |
| P5 | 两条平行管线（Cordis 内核线 / PluginManager+ScriptRuntime 老线）职责重叠 | 维护混乱 |
| P6 | kernel 能力归属靠 @Volatile 全局变量 + runBlocking 串行化，跨线程语义脆弱 | 隐患 |

## Requirements

### R1 崩溃根治：插件依赖解析安全化

**User Story:** AS 用户, I want 打开任意页面不因插件系统崩溃, so that 应用稳定可用。

#### Acceptance Criteria

1. WHEN 用户打开 WebViewPage 且 pluginId 为空, THE 宿主 SHALL 零插件依赖解析（主线程不触及 CordisPluginBridge/ChatService 任何构造）。
2. WHEN 依赖图中任一构造抛异常, THE 宿主 SHALL 将异常隔离在插件子系统边界内并向 UI 呈现降级状态, SHALL 保持页面可打开。
3. WHILE 依赖预热未完成, THE 插件相关 UI SHALL 呈现加载态并于完成后自动恢复, SHALL 阻塞主线程。

### R2 插件能力缝真实接线

**User Story:** AS 插件开发者, I want 声明的每个能力都有真实实现, so that 插件能做有意义的事。

#### Acceptance Criteria

1. WHEN 插件声明 llm 能力并调用 infer, THE 宿主 SHALL 支持传入完整消息列表（含历史上下文）并流式返回。
2. WHEN 插件注册工具到 tools 缝, THE 聊天工具列表 SHALL 实时出现该工具（产生 tools/change 并被聊天管线消费）。
3. WHEN 插件声明 sessions 能力, THE 插件 SHALL 可读取会话上下文并注入消息。
4. IF 插件声明了宿主未实现的能力, THE 宿主 SHALL 在安装时明确提示"该能力暂不可用", SHALL 静默失败。

### R3 面板插件运行现代化

**User Story:** AS 面板插件用户, I want 面板流畅不卡顿、状态实时更新, so that 插件体验接近原生。

#### Acceptance Criteria

1. WHEN 面板调用 agent.run 等长任务, THE 宿主 SHALL 异步执行并以事件流实时推送进度, SHALL 阻塞 JS 桥线程。
2. WHEN 生成过程产生增量内容, THE 面板 SHALL 能订阅流式事件（推送模型）, SHALL 轮询。
3. WHEN 插件面板启动, THE 宿主 SHALL 保证 Cordis 能力可用性确定（加载完成前显示明确的等待/失败态）, SHALL 出现"时有时无"竞态。

### R4 插件可视化中心（UI 重构核心）

**User Story:** AS 用户, I want 一个统一的插件中心看到全部插件及其功能, so that 插件可发现、易管理、真有用。

#### Acceptance Criteria

1. THE 宿主 SHALL 提供插件中心页：已安装插件以卡片呈现（图标、名称、版本、能力徽章、运行状态、快捷开关）。
2. WHEN 用户点击插件卡片, THE 宿主 SHALL 进入插件详情：功能说明、配置表单、权限（能力缝）清单、工具/面板/提示词入口。
3. WHEN 插件提供面板, THE 用户 SHALL 能从插件中心一键打开面板, SHALL 被迫寻找 extensionPoints 入口。
4. WHEN 插件注册的工具被聊天使用, THE 聊天界面 SHALL 在工具调用块中显示插件名与工具名。
5. THE 宿主 SHALL 在抽屉/设置保留插件中心入口，市场页并入插件中心。

### R5 双管线统一

**User Story:** AS 维护者, I want 单一插件运行管线, so that 行为一致可维护。

#### Acceptance Criteria

1. THE 插件注册/工具/提示词/Hook SHALL 收敛到 Cordis 内核为单一事实源，ScriptRuntime 老线作为执行器被内核调度。
2. WHEN 任一管线改动, THE 行为 SHALL 在两条路径下一致。

### R6 插件生命周期与安全

**User Story:** AS 用户, I want 插件故障不拖垮宿主, so that 我敢于安装第三方插件。

#### Acceptance Criteria

1. WHEN 插件 JS 执行抛异常或超时, THE 宿主 SHALL 捕获并向调用方返回结构化错误, SHALL 崩溃或冻结。
2. WHEN 插件被禁用/卸载, THE 宿主 SHALL 逆序清理其工具/监听器/面板实例并从聊天工具列表移除。
3. THE 宿主 SHALL 维持现有插件数据沙箱（filesDir/script-data/<id>/）与安装安全校验（路径穿越过滤、schema 归一化）。

## Decisions（用户已决策）

1. **兼容策略**：兼容是迁移手段而非目标——旧 plugin.json / 四个市场源保留自动迁移与导入，但新体系以"真正能用、人性化"为基准自由重构 manifest，现有设计中不可用、不人性化的部分（tools 缝空壳、能力时有时无、工具藏于元工具背后）一律重做。
2. **分期**：两期交付。一期 = 崩溃根治 + 能力缝真实接线 + 面板运行现代化（data 层为主）；二期 = 插件中心 UI + 双轨面板 + 双管线统一 + 市场信任模型。
3. **面板形态**：双轨混合。简单面板走声明式 UI schema（JSON 由宿主渲染为原生 Compose），复杂面板走 WebView（注入宿主设计体系）。

## 市场与 UI 调研洞察（2026-09 联网调研：DSH Market / CodeBuddy / ZCode / LangChain declarative UI / JSWidget）

1. **信任模型**：安装前做能力预检（"已证明可装"而非"大概能装"）；失败自动回滚；安装状态可视化流转（Installing → Installed / Failed）。
2. **权限披露**：安装确认页逐项展示插件声明的能力缝（会以宿主权限运行 llm/网络/文件等），进入市场精选列表代表完成安全审查的误导要规避。
3. **插件详情证据链**：用途、最近更新时间、维护活跃度、来源与 License、兼容性（宿主版本）——生态成熟后用户核心问题从"去哪找"变为"该不该把权限交给它"。
4. **组件目录护栏（声明式 UI）**：插件只能使用宿主预注册的类型化组件目录（卡片/文本/按钮/图表/列表/表单），输出永远可预测可安全渲染；支持渐进式渲染与双向交互（操作回传插件逻辑）。
5. **市场体验**：跨源统一搜索、分类筛选、Top/新排序、截图预览、一键安装即生效（无需重启）。

### R7 安装信任模型（二期）

**User Story:** AS 用户, I want 安装前明确知道插件要什么权限、装完确定能用, so that 我敢放心安装。

#### Acceptance Criteria

1. WHEN 用户在市场点击安装, THE 宿主 SHALL 展示该插件声明的能力缝清单与用途说明, 用户确认后才继续。
2. WHILE 安装进行中, THE UI SHALL 呈现状态流转（安装中→已安装/失败原因）, 失败时 SHALL 自动回滚到安装前状态。
3. WHEN 插件详情打开, THE 宿主 SHALL 展示版本、来源、最近更新时间、所需宿主版本兼容性。
4. WHEN 插件声明的能力宿主未实现, THE 安装确认页 SHALL 明确标注"该能力暂不可用"（联动 R2.4）。
