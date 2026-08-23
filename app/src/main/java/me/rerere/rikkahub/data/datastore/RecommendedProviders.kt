package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting

/**
 * 内置推荐/预览模板已全部移除，供应商页改为简洁的 Agent 平台选择器。
 * 保留空列表以兼容旧序列化数据读取。
 */
val RECOMMENDED_PROVIDERS: List<ProviderSetting> = emptyList()
