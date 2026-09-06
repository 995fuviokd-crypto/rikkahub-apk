package me.rerere.rikkahub.data.ai.engine

import me.rerere.rikkahub.data.ai.transformers.PlaceholderCtx

object CbsMacroEngine {
    private val RANDOM_PATTERN = Regex("\\{\\{random:([^}]+)\\}\\}")
    private val ROLL_PATTERN = Regex("\\{\\{roll:(\\d+)\\}\\}")
    private val PICK_PATTERN = Regex("\\{\\{pick:([^}]+)\\}\\}")
    private val REVERSE_PATTERN = Regex("\\{\\{reverse:([^}]+)\\}\\}")
    private val COMMENT_PATTERN = Regex("\\{\\{//[^}]*\\}\\}")

    fun resolve(text: String, ctx: PlaceholderCtx? = null): String {
        return text
            .let { removeComments(it) }
            .let { resolveRandom(it) }
            .let { resolveRoll(it) }
            .let { resolvePick(it) }
            .let { resolveReverse(it) }
    }

    private fun removeComments(text: String): String {
        return COMMENT_PATTERN.replace(text, "")
    }

    private fun resolveRandom(text: String): String {
        return RANDOM_PATTERN.replace(text) { match ->
            val options = match.groupValues[1].split("|")
            if (options.isEmpty()) {
                match.value
            } else {
                options.random().trim()
            }
        }
    }

    private fun resolveRoll(text: String): String {
        return ROLL_PATTERN.replace(text) { match ->
            val max = match.groupValues[1].toIntOrNull()
            if (max == null || max < 1) {
                match.value
            } else {
                (1..max).random().toString()
            }
        }
    }

    private fun resolvePick(text: String): String {
        return PICK_PATTERN.replace(text) { match ->
            val parts = match.groupValues[1].split(",")
            if (parts.isEmpty()) {
                match.value
            } else {
                parts.random().trim()
            }
        }
    }

    private fun resolveReverse(text: String): String {
        return REVERSE_PATTERN.replace(text) { match ->
            match.groupValues[1].reversed()
        }
    }
}
