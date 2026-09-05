package me.rerere.rikkahub.ui.components.webview

import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web 轨设计体系注入脚本生成测试（design.md D2.2 / 7.4）：
 * 生成合法 JS（含 CSS 变量与 cordis 样式），幂等守卫存在
 */
class CordisDesignTokensTest {

    @Test
    fun `injection js contains material css variables and cordis styles`() {
        val js = CordisDesignTokens.injectionJs(lightColorScheme())
        assertTrue(js.contains("--md-primary"))
        assertTrue(js.contains("--md-on-surface"))
        assertTrue(js.contains(".cordis-card"))
        assertTrue(js.contains(".cordis-btn"))
        assertTrue(js.contains("__cordisDesignInjected"))
    }

    @Test
    fun `injection js escapes newlines and quotes safely`() {
        val js = CordisDesignTokens.injectionJs(lightColorScheme())
        // 生成的 JS 是单行字符串字面量拼接，不应出现裸换行导致的语法破坏
        assertFalse(js.lines().any { it.trim().startsWith("body { background") })
        assertFalse(js.contains("\"\"\""))
    }
}
