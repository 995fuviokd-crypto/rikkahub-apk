# 上下文压缩功能重设计 - 需求文档

## 调研输入

### Claude Code 官方机制（Anthropic docs + GitHub 官方回复）
- `/compact` 显式手动命令；auto-compact 在上下文接近窗口上限时自动触发（默认阈值随版本变化，曾为 ~95%，2.1.193 起为 50%）
- 阈值可通过环境变量配置（`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`、`CLAUDE_CODE_AUTO_COMPACT_WINDOW`），官方实验默认 400k
- 压缩使 prompt cache 全部失效（前缀匹配），导致下一轮显著变慢变贵
- 上下文可视化（/context）：system prompt、工具 schema、MEMORY.md、消息占比一目了然
- MEMORY.md 持久记忆：跨会话保存关键决策与偏好，压缩后核心信息不丢
- 规划方向：智能截断、修剪、调度次要任务

### 用户评价（HN 54 条评论）
- **核心抱怨 1 - 有损压缩丢上下文**：压缩后忘记用户反复强调的内容；用户 rsafaya 认为"保留完整历史、仅在推理时裁剪"是真正更好的模型（lossy compression 之上）
- **核心抱怨 2 - 静默行为变化**：默认阈值悄然改动，压缩时机不可预期
- **核心抱怨 3 - 长上下文质量下降**：上下文越大响应越差，用户宁可自己 /clear
- **正面**：阈值可配置广受欢迎；长会话记录类场景 auto-compact 体验好；压缩前给出警告是共识期望
- **用户自创的替代方案**：手动 /compact、先让 agent 写 summary/memory 再清理、commit 时强制更新 memory 文件

## 需求（EARS）

### 现状确认（保留项）
RikkaHub 现有实现已符合 rsafaya 所述的"更优模型"：
- WHEN 压缩完成，THE SYSTEM SHALL 保留全部历史消息本体于 messageNodes（有损只发生在送入 LLM 的上下文，用户可见可回溯）
- IF 用户展开折叠卡片，THE SYSTEM SHALL 展示滚动摘要全文与被压缩的原始消息
- THE SYSTEM SHALL 提供 autoCompressEnabled / autoCompressContextPercent / autoCompressMaxMode / compressModelId / compressPrompt 设置项

### 新增需求
1. WHEN 进入会话，THE SYSTEM SHALL 在输入区显示当前上下文用量（估算 token / 窗口大小百分比），超过阈值时视觉预警
2. WHEN 自动压缩即将触发，THE SYSTEM SHALL 在列表尾部提示"历史过长，正在压缩"（已有），并在压缩完成后以 Snackbar 告知摘要已生成、可展开查看
3. WHEN 生成压缩摘要，THE SYSTEM SHALL 使用结构化摘要 prompt（当前任务与目标 / 已完成步骤与关键结果 / 用户偏好与硬性约束 / 关键实体与数据 / 未决问题与下一步），替代自由摘要
4. IF 历史中存在旧工具调用结果（tool parts 超过保留窗口），THE SYSTEM SHALL 在压缩前先对超出保留范围的旧 tool 结果做占位截断（micro-trim），降低对摘要的依赖
5. WHEN 压缩完成，THE SYSTEM SHALL 从摘要中提取"用户偏好/约束"条目写入该 Assistant 的持久记忆（Memory），保障跨会话连续性
6. IF 用户在压缩前点击输入区用量指示，THE SYSTEM SHALL 展示明细弹窗（消息 token、工具结果 token、系统 prompt token、压缩阈值线）
7. THE SYSTEM SHALL 支持手动触发压缩（聊天菜单"压缩历史对话"，已有入口保持）
