# Requirements: RikkaHub 整体人性化整改（rikkahub-ux-overhaul）

## Introduction

以最终用户体验为基准对 RikkaHub 全面整改。调研依据：双路代码勘探（UI/UX 全景 + 启动/数据/稳定性）+ 竞品痛点调研（Chatbox/Cherry Studio 用户投诉：错误提示不友好、API 配置门槛高、数据丢失、卡顿、沉浸感破坏）。

**核心结论**：工程底座扎实（流式优化、Room 迁移链、备份体系、连接测试均上乘），短板集中在**失败路径**与**新用户路径**——成功流程顺滑，出错时和第一次使用时体验断层。

## 需求板块

### A 可靠与信任（P0）

- **A1 错误处理人性化**：错误卡取消 5 秒自动消失、增加"重试"按钮；错误分类映射为人话（401=密钥错误、429=限流、超时=网络问题）+ 折叠详情；工具卡片堆栈折叠。
- **A2 抽屉删除会话加确认 + Snackbar 撤销**（对齐历史页既有模式），消除最大数据丢失风险点。
- **A3 会话批量操作**（多选删除/移动/导出）+ 单会话 JSON 导出。
- **A4 稳定性兜底分级**：DataStore 读取失败 halt(1) 杀进程改为重试+降级；DB 静默跳页丢弃消息改为损坏提示；http 下载加超时。
- **A5 KeepAliveService 默认关闭**（生成期保活已有独立 FGS）。

### B 新用户路径（P0）

- **B1 首启 onboarding 向导**：选 Provider → 填 API Key → 连通测试 → 选默认模型一条龙；完成前不落入空白聊天页。
- **B2 空会话引导态**：新会话显示欢迎语、示例 prompt、Provider 未配置时的引导卡片（替代"发消息才报错"）。
- **B3 Provider 编辑未保存提醒**（返回时检测暂存差异弹确认）。

### C 性能与电耗（P1）

- **C1 长会话数据路径**：打开会话按需分页（最近 N 条 + 上滑加载历史）；非流式保存改节点级 diff upsert（替换全删重插）。
- **C2 列表页分页**：HistoryPage 切换现成 LightConversationEntity Paging。
- **C3 主线程 IO 清理**：EmojiData 后台预热；附件选择文件复制改 IO 协程。
- **C4 WebView 生命周期**：onPause/pauseTimers、onResume/resumeTimers。
- **C5 设置写入放大**：incrementLaunchCount 的全量 Settings 写回收敛为计数专用存储。

### D 界面一致性与无障碍（P1）

- **D1 硬编码中文迁移 stringResource**（抽屉扩展功能区、设置页、聊天页流式提示等 10+ 处），恢复 6 语言一致性。
- **D2 contentDescription 补全**：关键图标按钮优先（连接测试、消息操作等 65% 空缺）。
- **D3 抽屉信息架构重组**：分区收纳（主会话区扩展功能默认折叠、更新卡/备份卡合并为一条）、支持配置显隐。
- **D4 抽屉即时会话过滤框**：免跳页 3 步搜索。

### E 插件系统（P0-P2，独立 spec 联动）

- 按既有 spec `.monkeycode/specs/plugin-system-rebuild/` 两期执行（崩溃根治 → 能力接线 → 插件中心/双轨面板），Phase 1 与本整改 P0 并行。

## 优先级与分期

| 期 | 内容 | 理由 |
|----|------|------|
| P0 | A1 A2 A4 A5 + B1 B2 B3 + E-Phase1 | 失败路径与新用户路径，收益最大风险最小 |
| P1 | C1-C5 + D1 D2 | 性能感知 + 语言一致性 |
| P2 | A3 + D3 D4 | 结构性优化 |

## 约束

- 不构建（用户指令，验证由用户本机执行）
- 与官方同步冲突面最小化：改动尽量收敛在 UI 层与局部工具类，ChatService/GenerationHandler 谨慎触碰
- 遵循现有代码风格与 stringResource 模式（AGENTS.md）
