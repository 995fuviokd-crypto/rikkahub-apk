package me.rerere.rikkahub.service

import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动压缩阈值解析：阈值 = 模型上下文窗口 × (Max 模式 ? 3 : 1) × 百分比。
 * 覆盖注册模型（有 contextLength）、未注册模型（回退默认窗口 128K）、Max 模式放大、百分比 clamp。
 */
class AutoCompressThresholdTest {

    @Test
    fun `registered model window times percent`() {
        // deepseek-v4-flash 官方注册上下文 1M (1_000_000)
        val model = Model(modelId = "deepseek-v4-flash")
        val settings = Settings(autoCompressEnabled = true, autoCompressContextPercent = 60, autoCompressMaxMode = false)
        assertEquals(1_000_000 * 60 / 100, resolveAutoCompressThreshold(model, settings))
    }

    @Test
    fun `unknown model falls back to default window`() {
        val model = Model(modelId = "custom-unknown-model")
        val settings = Settings(autoCompressEnabled = true, autoCompressContextPercent = 50, autoCompressMaxMode = false)
        assertEquals(128_000 * 50 / 100, resolveAutoCompressThreshold(model, settings))
    }

    @Test
    fun `max mode triples the base window`() {
        val model = Model(modelId = "deepseek-v4-flash")
        val settings = Settings(autoCompressEnabled = true, autoCompressContextPercent = 80, autoCompressMaxMode = true)
        assertEquals(1_000_000 * 3 * 80 / 100, resolveAutoCompressThreshold(model, settings))
    }

    @Test
    fun `percent clamped to one hundred`() {
        val model = Model(modelId = "deepseek-v4-flash")
        assertEquals(1_000_000 * 1 / 100, resolveAutoCompressThreshold(model, Settings(autoCompressContextPercent = 0)))
        assertEquals(1_000_000 * 100 / 100, resolveAutoCompressThreshold(model, Settings(autoCompressContextPercent = 500)))
    }

    @Test
    fun `context length resolution falls back for unknown models`() {
        assertEquals(128_000, resolveContextLength(Model(modelId = "nope-model")))
        assertEquals(1_000_000, resolveContextLength(Model(modelId = "deepseek-v4-flash")))
    }
}
