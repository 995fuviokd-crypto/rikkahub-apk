package me.rerere.rikkahub.data.ai.workflow

/**
 * 变量模板渲染：解析 `{{step.N.output}}` 与 `{{input.NAME}}` 占位符。
 * 引用缺失时替换为空字符串，未匹配的占位符原样保留。
 */
object TemplateRenderer {
    private val STEP_PATTERN = Regex("""\{\{\s*step\.(\d+)\.output\s*\}\}""")
    private val INPUT_PATTERN = Regex("""\{\{\s*input\.([^}]+?)\s*\}\}""")

    fun render(
        template: String,
        stepOutputs: Map<Int, String>,
        input: Map<String, String> = emptyMap(),
    ): String {
        var result = STEP_PATTERN.replace(template) { match ->
            val index = match.groupValues[1].toIntOrNull()
            index?.let { stepOutputs[it].orEmpty() } ?: ""
        }
        result = INPUT_PATTERN.replace(result) { match ->
            input[match.groupValues[1]].orEmpty()
        }
        return result
    }
}
