# Tasklist: RikkaHub 整体人性化整改（P0 期）

> P0 任务 1-7；P1/P2 见 requirements.md，P0 完成且用户本机验证后再启动。

- [x] 1. 错误处理人性化（A1）
  - [x] 1.1 ErrorCard 常驻化与重试
    - 修改 `ui/components/ui/ErrorCard.kt`：移除 5 秒自动 dismiss；增加可选 onRetry 参数与"重试"按钮；错误正文改可展开折叠（默认一句话摘要）
  - [x] 1.2 错误分类人话映射
    - 新建 `ui/components/ui/ChatErrorMapper.kt`：按 HTTP 状态/异常类型（401/403/429/超时/DNS/取消）映射本地化摘要 + 建议动作；ErrorCard 接入
  - [x] 1.3 工具失败卡片堆栈折叠
    - `ui/components/message/ChatMessageTools.kt`：工具输出 `{"error": "..."}` 默认显示首行、点击展开完整堆栈

- [x] 2. 抽屉删除会话防误删（A2）
  - [x] 2.1 删除确认对话框
    - 修改 `ui/pages/chat/ChatDrawer.kt`：deleteConversation 前弹确认
  - [x] 2.2 Snackbar 撤销
    - 删除前完整备份对话 + Snackbar"已删除/撤销"，撤销时 insertConversation 恢复（对齐 HistoryPage 既有撤销模式）

- [x] 3. 稳定性兜底分级（A4）
  - [x] 3.1 halt 降级
    - 修改 `utils/CoroutineUtils.kt`：toMutableStateFlow 失败改为有限重试（退避）→ 降级 initial 值 + 日志，移除 Runtime.halt(1)
  - [x] 3.2 DB 静默丢页可见化
    - `data/repository/ConversationRepository.kt`：SQLiteBlobTooBigException 跳页时统计损坏节点数，经 corruptionEvents 发出，ChatService 提示"部分历史消息无法读取"
  - [x] 3.3 http 下载超时
    - 修改 `data/files/FilesManager.kt`：saveMessageImage 的 HttpURLConnection 设 connect/read 超时（10s/30s）

- [x] 4. KeepAlive 默认关闭（A5）
  - `data/datastore/PreferencesStore.kt`：读取逻辑改 `preferences[KEEP_ALIVE_ENABLED] == true`（未显式设置即关闭）；已有显式设置的用户不受影响

- [x] 5. 空会话引导态（B2）
  - [x] 5.1 欢迎与示例
    - 修改 `ui/pages/chat/ChatList.kt`：ChatEmptyState 显示欢迎语 + 4 个可点示例 prompt，点击填入输入框
  - [x] 5.2 未配置 Provider 引导卡
    - 未配置 Provider 时引导卡带"去配置模型"直达 SettingModels；已配置则展示示例 prompt

- [x] 6. 首启 onboarding 向导（B1）
  - [x] 6.1 向导页骨架
    - 新建 `ui/pages/onboarding/OnboardingPage.kt`：多步向导（选模板/填 Key+BaseURL/测试+选模型），进度指示，可返回
  - [x] 6.2 首启判定与路由
    - RouteActivity startScreen：未完成 onboarding（preference `onboarding_completed`）时路由 Screen.Onboarding；完成/跳过均写标记
  - [x] 6.3 向导复用既有能力
    - 复用 ProviderSetting 模板；连通测试与 listModels 复用既有 Provider 能力（OnboardingVM）

- [x] 7. Provider 未保存提醒（B3）
  - `ui/pages/setting/SettingProviderDetailPage.kt`：BackHandler + 返回按钮双路径检测暂存差异，弹"未保存更改：保存/放弃"对话框

## P1（预告，暂不执行）

- 8. 长会话分页加载 + 节点级 diff 保存（C1）
- 9. HistoryPage 切 Paging（C2）
- 10. EmojiData 预热 + 附件复制 IO 化（C3）
- 11. WebView pauseTimers（C4）
- 12. 硬编码中文迁移（D1）+ contentDescription（D2）

## P2（预告，暂不执行）

- 13. 会话批量操作 + 单会话 JSON 导出（A3）
- 14. 抽屉信息架构重组（D3）+ 即时过滤框（D4）
