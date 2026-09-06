package me.rerere.rikkahub.data.memory

import java.util.Locale

/**
 * 检索门控与查询词法化（移植自 scope-recall-hermes 的 gating.py）。
 *
 * 保守门控：空查询、寒暄、过短查询直接跳过检索；
 * 词法化：英文/数字词 + 中文连续文本的确定性 n-gram 分段。
 */
object MemoryGating {

    private val TRIVIAL_RE = Regex(
        "^(?:ok|okay|kk|k|yes|no|yep|nope|sure|thanks|thank you|thx|ty|got it|roger|" +
            "understood|noted|acknowledged|done|" +
            "hi|hello|hey|yo|早|早安|你好|嗨|在吗|在嗎|谢谢|謝謝|收到|明白|明白了|了解|了解了|好的|好" +
            ")(?:[!！,.。?？~\\s]*)$",
        RegexOption.IGNORE_CASE,
    )
    private val WORD_RE = Regex("[a-zA-Z0-9]{2,}|[\\u4e00-\\u9fff]{2,}")
    private val CJK_TOKEN_RE = Regex("^[\\u4e00-\\u9fff]+$")

    private val CJK_STOPWORDS = setOf(
        "一个", "什么", "哪个", "哪里", "哪", "哪儿", "何处", "为什么", "以及", "当前",
        "告诉", "告诉我", "多少", "如今", "怎么", "怎样", "是否", "是不是", "是", "有没有",
        "最近", "核验", "现在", "的", "请", "目前", "还是", "这个", "那个", "或者",
    )

    private val SEMANTIC_STOPWORDS = CJK_STOPWORDS + setOf(
        "a", "an", "are", "at", "be", "current", "currently", "do", "does", "for", "how",
        "in", "is", "my", "now", "of", "on", "the", "to", "what", "when", "where", "which", "who",
    )

    private val CURRENT_STATE_RE = Regex(
        "(?:\\b(?:current|currently|latest|newest|now|today)\\b|当前|目前|现在|如今|最新)",
        RegexOption.IGNORE_CASE,
    )
    private val HISTORICAL_STATE_RE = Regex(
        "(?:\\b(?:previously|before|history|historical|formerly|used\\s+to|as\\s+of)\\b|" +
            "之前|以前|过去|当时|曾经|历史|原来)",
        RegexOption.IGNORE_CASE,
    )
    private val LOCATION_QUERY_RE = Regex("(?:在哪里|在哪|哪儿|何处|什么位置)")
    private val OPERATING_SYSTEM_QUERY_RE = Regex(
        "(?:(?:跑|用|使用|运行).{0,4}(?:什么|哪个|哪种)(?:操作)?系统|(?:什么|哪个|哪种)(?:操作)?系统)",
    )
    private val TIMEZONE_QUERY_RE = Regex("(?:\\btime\\s*zone\\b|\\btimezone\\b|时区)", RegexOption.IGNORE_CASE)

    private val LOCATION_INTENT_EVIDENCE_TERMS =
        listOf("位置", "地址", "路径", "目录", "主机", "本机", "运行环境")
    private val OPERATING_SYSTEM_INTENT_TERMS = listOf(
        "操作系统", "运行环境", "主机", "本机", "windows", "linux", "macos",
    )
    private val TIMEZONE_INTENT_TERMS = listOf("时区", "timezone", "time zone", "utc", "gmt")

    fun isTrivial(text: String?): Boolean =
        text?.trim()?.let { TRIVIAL_RE.matches(it) } ?: true

    fun shouldSkipRetrieval(query: String?, minLength: Int): Boolean {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return true
        if (isTrivial(q)) return true
        if (q.length < minLength) return true
        return false
    }

    private fun deterministicCjkSegments(token: String): List<String> {
        var reduced = token
        CJK_STOPWORDS.sortedByDescending { it.length }.forEach { stopword ->
            reduced = reduced.replace(stopword, " ")
        }
        val segments = mutableListOf<String>()
        reduced.split(' ').forEach { piece ->
            if (piece.length >= 2) segments.add(piece)
            if (piece.length <= 2) return@forEach
            for (width in intArrayOf(2, 3)) {
                var index = 0
                while (index + width <= piece.length) {
                    segments.add(piece.substring(index, index + width))
                    index++
                }
            }
        }
        return segments
    }

    private fun cjkQuerySegments(token: String): List<String> {
        if (token.length < 4 || !CJK_TOKEN_RE.matches(token)) return emptyList()
        val raw = deterministicCjkSegments(token)
        val positioned = linkedMapOf<String, Int>()
        raw.forEach { term ->
            val t = term.trim()
            if (t.length < 2 || t == token || t in CJK_STOPWORDS || !CJK_TOKEN_RE.matches(t)) return@forEach
            positioned.putIfAbsent(t, token.indexOf(t))
        }
        val terms = positioned.filterKeys { term ->
            term.length > 2 || positioned.keys.none { other ->
                other != term && other.length > term.length && term in other
            }
        }.keys
        return terms
            .sortedWith(compareByDescending<String> { it.length }.thenBy { positioned[it] }.thenBy { it })
            .take(11)
    }

    /** 返回去重的查询词集合，包含 CJK 分段。 */
    fun queryTokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun append(token: String) {
            if (token in seen) return
            seen.add(token)
            tokens.add(token)
        }
        WORD_RE.findAll(text.lowercase(Locale.ROOT)).forEach { match ->
            val token = match.value
            append(token)
            cjkQuerySegments(token).forEach(::append)
        }
        return tokens
    }

    /** 语义查询词（去停用词），用于召回与评分。 */
    fun semanticQueryTokens(text: String): List<String> {
        val raw = queryTokens(text)
        val output = mutableListOf<String>()
        raw.forEach { token ->
            val normalized = token.lowercase(Locale.ROOT).trim()
            if (normalized.isEmpty() || normalized in SEMANTIC_STOPWORDS) return@forEach
            if (
                CJK_TOKEN_RE.matches(normalized) && normalized.length >= 4 &&
                CJK_STOPWORDS.any { it in normalized } &&
                raw.any { other -> other != normalized && other.length >= 2 && other in normalized }
            ) {
                return@forEach
            }
            if (normalized !in output) output.add(normalized)
        }
        return output
    }

    /** 查询意图扩展词，用于特定答案形态问题的检索提示。 */
    fun queryIntentTerms(text: String): List<String> {
        val normalized = text.trim().lowercase(Locale.ROOT)
        val output = linkedSetOf<String>()
        if (LOCATION_QUERY_RE.containsMatchIn(normalized)) {
            LOCATION_INTENT_EVIDENCE_TERMS.forEach(output::add)
        }
        if (OPERATING_SYSTEM_QUERY_RE.containsMatchIn(normalized)) {
            OPERATING_SYSTEM_INTENT_TERMS.forEach(output::add)
        }
        if (TIMEZONE_QUERY_RE.containsMatchIn(normalized)) {
            TIMEZONE_INTENT_TERMS.forEach(output::add)
        }
        return output.toList()
    }

    fun matchedQueryIntentTerms(query: String, document: String): Boolean {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val normalizedDocument = document.trim().lowercase(Locale.ROOT)
        if (LOCATION_QUERY_RE.containsMatchIn(normalizedQuery)) {
            return LOCATION_INTENT_EVIDENCE_TERMS.any { it in normalizedDocument }
        }
        if (OPERATING_SYSTEM_QUERY_RE.containsMatchIn(normalizedQuery)) {
            val matched = OPERATING_SYSTEM_INTENT_TERMS.any { it in normalizedDocument }
            return matched && CURRENT_STATE_RE.containsMatchIn(normalizedDocument)
        }
        if (TIMEZONE_QUERY_RE.containsMatchIn(normalizedQuery)) {
            return TIMEZONE_INTENT_TERMS.any { it in normalizedDocument }
        }
        return false
    }

    fun queriesCurrentState(text: String): Boolean {
        val normalized = text.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty() || HISTORICAL_STATE_RE.containsMatchIn(normalized)) return false
        return CURRENT_STATE_RE.containsMatchIn(normalized) ||
            LOCATION_QUERY_RE.containsMatchIn(normalized) ||
            OPERATING_SYSTEM_QUERY_RE.containsMatchIn(normalized) ||
            TIMEZONE_QUERY_RE.containsMatchIn(normalized)
    }

    fun retrievalQueryTokens(text: String): List<String> =
        (semanticQueryTokens(text) + queryIntentTerms(text)).distinct()

    /** 轻量词干化。 */
    fun stemToken(token: String): String {
        if (token.isEmpty() || !token.all { it.isLetter() || it.isDigit() }) return token
        val t = token.lowercase(Locale.ROOT)
        if (t.length > 4 && t.endsWith("ies")) return t.dropLast(3) + "y"
        if (t.length > 4 && t.endsWith("ing")) {
            val stem = t.dropLast(3)
            return if (stem.length >= 2 && stem.last() == stem[stem.length - 2]) stem.dropLast(1) else stem
        }
        if (t.length > 3 && t.endsWith("ed")) {
            val stem = t.dropLast(2)
            return if (stem.length >= 2 && stem.last() == stem[stem.length - 2]) stem.dropLast(1) else stem
        }
        if (
            t.length > 4 && t.endsWith("es") &&
            !t.endsWith("ses") && !t.endsWith("xes") && !t.endsWith("zes") &&
            !t.endsWith("ches") && !t.endsWith("shes")
        ) {
            return t.dropLast(1)
        }
        if (t.length > 3 && t.endsWith("s") && !t.endsWith("ss")) return t.dropLast(1)
        return t
    }

    fun normalizedTokenSet(tokens: List<String>): Set<String> {
        val normalized = mutableSetOf<String>()
        tokens.forEach { token ->
            val t = token.lowercase(Locale.ROOT).trim()
            if (t.isEmpty()) return@forEach
            normalized.add(t)
            normalized.add(stemToken(t))
        }
        return normalized
    }

    fun dedupKey(text: String): String {
        val normalized = text.trim().lowercase(Locale.ROOT)
        val noPunctuation = Regex("[\\p{Punct}]").replace(normalized, "")
        return Regex("\\s+").replace(noPunctuation, " ")
    }

    fun ftsEscape(token: String): String = "\"" + token.replace("\"", " ") + "\""

    fun buildFtsQuery(tokens: List<String>): String {
        val safe = tokens.map(::ftsEscape).filter { it.isNotEmpty() }
        if (safe.isEmpty()) return ""
        return safe.take(12).joinToString(" OR ")
    }
}
