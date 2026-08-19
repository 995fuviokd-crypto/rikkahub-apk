package me.rerere.rikkahub.data.ai.workflow

/**
 * 变量模板渲染：解析 `{{node.<id>.output}}`、`{{step.N.output}}`（旧）与 `{{input.NAME}}` 占位符。
 * 引用缺失时替换为空字符串，未匹配的占位符原样保留。
 */
object TemplateRenderer {
    private val NODE_PATTERN = Regex("""\{\{\s*node\.([\w-]+)\.output(\|len)?\s*\}\}""")
    private val STEP_PATTERN = Regex("""\{\{\s*step\.(\d+)\.output\s*\}\}""")
    private val INPUT_PATTERN = Regex("""\{\{\s*input\.([^}]+?)\s*\}\}""")

    /**
     * 图节点输出渲染：`{{node.<id>.output}}` 优先，兼容 `{{step.N.output}}` 与 `{{input.NAME}}`。
     * `{{node.<id>.output|len}}` 渲染为输出长度。
     */
    fun render(
        template: String,
        nodeOutputs: Map<String, String> = emptyMap(),
        input: Map<String, String> = emptyMap(),
        stepOutputs: Map<Int, String> = emptyMap(),
    ): String {
        var result = NODE_PATTERN.replace(template) { match ->
            val value = nodeOutputs[match.groupValues[1]].orEmpty()
            if (match.groupValues[2].isNotEmpty()) value.length.toString() else value
        }
        result = STEP_PATTERN.replace(result) { match ->
            stepOutputs[match.groupValues[1].toIntOrNull() ?: -1].orEmpty()
        }
        result = INPUT_PATTERN.replace(result) { match ->
            input[match.groupValues[1]].orEmpty()
        }
        return result
    }
}
