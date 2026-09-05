package me.rerere.rikkahub.ui.components.webview

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb

/**
 * Web 轨设计体系注入（design.md D2.2 / R4.2）。
 *
 * 插件面板页面加载完成后注入：
 * 1. Material3 动态色 CSS 变量（--md-*，随系统深浅色与动态取色跟随宿主主题）
 * 2. cordis.css 轻量组件样式表（.cordis-card/.cordis-btn 等，插件页无需自带样式即可融入宿主观感）
 *
 * 插件侧引用示例：
 * ```css
 * .my-card { background: var(--md-surface-container); color: var(--md-on-surface); border-radius: 12px; }
 * ```
 */
object CordisDesignTokens {

    fun injectionJs(colorScheme: ColorScheme): String {
        val vars = listOf(
            "--md-primary" to colorScheme.primary,
            "--md-on-primary" to colorScheme.onPrimary,
            "--md-primary-container" to colorScheme.primaryContainer,
            "--md-on-primary-container" to colorScheme.onPrimaryContainer,
            "--md-secondary" to colorScheme.secondary,
            "--md-secondary-container" to colorScheme.secondaryContainer,
            "--md-on-secondary-container" to colorScheme.onSecondaryContainer,
            "--md-tertiary" to colorScheme.tertiary,
            "--md-surface" to colorScheme.surface,
            "--md-on-surface" to colorScheme.onSurface,
            "--md-surface-variant" to colorScheme.surfaceVariant,
            "--md-on-surface-variant" to colorScheme.onSurfaceVariant,
            "--md-surface-container" to colorScheme.surfaceContainer,
            "--md-surface-container-low" to colorScheme.surfaceContainerLow,
            "--md-surface-container-high" to colorScheme.surfaceContainerHigh,
            "--md-outline" to colorScheme.outline,
            "--md-outline-variant" to colorScheme.outlineVariant,
            "--md-error" to colorScheme.error,
            "--md-on-error" to colorScheme.onError,
        ).joinToString(";") { (name, color) ->
            "$name:#${Integer.toHexString(color.toArgb()).substring(2)}"
        }

        val css = """
            :root { $vars; }
            body { background: var(--md-surface); color: var(--md-on-surface);
                   font-family: system-ui, -apple-system, sans-serif; margin: 0; padding: 16px;
                   -webkit-text-size-adjust: 100%; }
            .cordis-card { background: var(--md-surface-container); border-radius: 12px;
                           padding: 12px 16px; margin-bottom: 12px; }
            .cordis-title { font-size: 1.05em; font-weight: 600; margin: 0 0 4px; }
            .cordis-subtitle { font-size: 0.85em; color: var(--md-on-surface-variant); margin: 0; }
            .cordis-btn { background: var(--md-primary); color: var(--md-on-primary); border: none;
                          border-radius: 999px; padding: 8px 18px; font-size: 0.9em; cursor: pointer; }
            .cordis-btn:active { opacity: 0.8; }
            .cordis-btn-secondary { background: var(--md-secondary-container);
                                    color: var(--md-on-secondary-container); }
            .cordis-input { background: var(--md-surface-container); color: var(--md-on-surface);
                            border: 1px solid var(--md-outline-variant); border-radius: 8px;
                            padding: 8px 12px; font-size: 0.9em; width: 100%; box-sizing: border-box; }
            .cordis-divider { border: none; border-top: 1px solid var(--md-outline-variant); margin: 12px 0; }
            .cordis-badge { display: inline-block; background: var(--md-secondary-container);
                            color: var(--md-on-secondary-container); border-radius: 999px;
                            padding: 2px 10px; font-size: 0.75em; }
        """.trimIndent()

        return """
            (function() {
              if (window.__cordisDesignInjected) return;
              window.__cordisDesignInjected = true;
              var style = document.createElement('style');
              style.id = 'cordis-design-tokens';
              style.textContent = ${jsStringLiteral(css)};
              document.head.appendChild(style);
            })();
        """.trimIndent()
    }

    private fun jsStringLiteral(text: String): String {
        val sb = StringBuilder("\"")
        for (ch in text) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '"' -> sb.append("\\\"")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> {}
                ch == '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.append("\"").toString()
    }
}
