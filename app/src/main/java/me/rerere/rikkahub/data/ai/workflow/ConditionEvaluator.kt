package me.rerere.rikkahub.data.ai.workflow

/**
 * 简单条件表达式求值器，用于 IF 节点。
 * 支持形如 `{{node.a.output}} > 1000`、`{{input.x}} == "abc"`、`{{node.a.output|len}} > 5` 的表达式。
 * 无比较运算符时，渲染后字符串非空即视为真。
 */
object ConditionEvaluator {

    fun eval(
        condition: String,
        nodeOutputs: Map<String, String>,
        input: Map<String, String>,
    ): Boolean {
        val rendered = TemplateRenderer.render(condition, nodeOutputs, input).trim()
        if (rendered.isEmpty()) return false
        val regex = Regex("""^(.*?)\s*(==|!=|>=|<=|>|<)\s*(.*?)$""")
        val match = regex.matchEntire(rendered) ?: return true
        val left = evaluateOperand(match.groupValues[1].trim())
        val right = evaluateOperand(match.groupValues[3].trim())
        val leftNum = left.toDoubleOrNull()
        val rightNum = right.toDoubleOrNull()
        return when (match.groupValues[2]) {
            "==" -> if (leftNum != null && rightNum != null) leftNum == rightNum else left == right
            "!=" -> if (leftNum != null && rightNum != null) leftNum != rightNum else left != right
            ">" -> requireNumbers(leftNum, rightNum) { a, b -> a > b }
            ">=" -> requireNumbers(leftNum, rightNum) { a, b -> a >= b }
            "<" -> requireNumbers(leftNum, rightNum) { a, b -> a < b }
            "<=" -> requireNumbers(leftNum, rightNum) { a, b -> a <= b }
            else -> false
        }
    }

    private inline fun requireNumbers(
        left: Double?,
        right: Double?,
        op: (Double, Double) -> Boolean,
    ): Boolean {
        if (left == null || right == null) return false
        return op(left, right)
    }

    private fun evaluateOperand(raw: String): String {
        return when {
            raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 -> raw.substring(1, raw.length - 1)
            raw.startsWith("'") && raw.endsWith("'") && raw.length >= 2 -> raw.substring(1, raw.length - 1)
            raw.endsWith("|len") -> raw.dropLast(4).trim().length.toString()
            else -> raw
        }
    }
}
