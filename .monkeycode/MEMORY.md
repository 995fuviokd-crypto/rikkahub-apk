# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Entries

[User Instruction Summary]
- Date: 2026-09-06
- Context: RikkaHub 构建与推送任务
- Instructions:
  - 全部任务完成后才开始构建 APK，按流程执行，禁止跳过设计/实现阶段直接构建
  - 构建时必须限制内存与 CPU（background_terminal_create + memory_percent/cpu_percent + -Dorg.gradle.jvmargs），防止环境卡死
  - 中途不得停下来，连续推进直到完成
  - 压缩功能展示以"仅显示已压缩完成的提示"为准，参考其他 agent（Claude Code）的机制设计，同时结合实际情况取舍
  - 推送使用用户提供的 GitHub token 配置 origin URL（凭据只写入 remote URL，不写入代码或提交文件）

[Project Knowledge Summary]
- Date: 2026-09-06
- Context: Agent 执行 RikkaHub 构建、调试压缩功能期间
- Category: Build Methods
- Instructions:
  - 构建：`./gradlew assembleDebug -Dorg.gradle.jvmargs="-Xmx3g -Xms1g" -Dkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`，用 background_terminal_create 限 memory_percent 55 / cpu_percent 200，总耗时 4-8 分钟
  - 本环境网络不可达 firebase：app/build.gradle.kts 已用 `tasks.matching { it.name.startsWith("uploadCrashlyticsMappingFile") }.configureEach { enabled = false }` 永久禁用 mapping 上传，构建失败报 SocketException: Network is unreachable 时先查此配置
  - 全量单测命令：`./gradlew :app:testDebugUnitTest :ai:testDebugUnitTest`（约 3 分钟，217+ tests）
  - APK 产物路径：app/build/outputs/apk/debug/app-universal-debug.apk（约 222MB，minifyEnabled=true 的 debug 构建）
  - 推送：origin 指向 github.com/995fuviokd-crypto/rikkahub-apk.git，凭据经 token 内嵌 remote URL；官方上游 remote 名为 official
  - 压缩实现位置：ChatService.kt（compressConversation ~L1543、autoCompressConversation ~L1707、storePreferencesFromSummary）、ConversationCompressor.kt（纯计算 + microTrimToolOutputs）、CompressPrompt.kt（五段式结构化摘要 prompt）、ChatList.kt（CompressedHistoryGroup 仅一行提示，消息本体正常渲染）
