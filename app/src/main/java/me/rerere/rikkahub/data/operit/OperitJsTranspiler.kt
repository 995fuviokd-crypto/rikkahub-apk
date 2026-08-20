package me.rerere.rikkahub.data.operit

/**
 * 将 Operit 脚本中的 async/await 转换为可同步执行的 generator 形式。
 *
 * QuickJS wrapper（wang.harlon.quickjs 3.2.3）不调度微任务队列，原生 async 函数
 * 返回的 Promise 永远无法 resolve。Operit 脚本的工具函数均为 async 形式，且其
 * 依赖的 Tools.* 运行时由本 App 以同步方式注入，因此可以把
 *   `async function f(a) { ...; const r = await Tools.X.y(a); ...; return r; }`
 * 转换为
 *   `function f(a) { return __operitRunGen(function*() { ...; const r = yield Tools.X.y(a); ...; return r; }); }`
 * 由 __operitRunGen 同步驱动 generator，最终拿到返回值。
 *
 * 转换是词法安全的：字符串、模板字符串、注释、正则字面量均被原样保留。
 */
object OperitJsTranspiler {

    /** 注入到目标脚本头部的运行时，用于同步驱动 generator */
    const val RUN_GEN_RUNTIME: String = """
function __operitRunGen(gen, self) {
    var g = gen;
    if (self !== undefined && self !== null) g = gen.call(self);
    var step = g.next();
    while (!step.done) { step = g.next(step.value); }
    return step.value;
}
"""

    /** 执行转换，返回可在 QuickJS 中评估的脚本 */
    fun transpile(source: String): String {
        val tokens = tokenize(source)
        val out = StringBuilder(source.length + 256)
        val stack = mutableListOf<BraceKind>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.text == "async" && !t.inString -> {
                    val nextIdx = nextSignificant(tokens, i)
                    if (nextIdx < tokens.size && tokens[nextIdx].text == "function") {
                        val fnIdx = nextIdx
                        var k = nextSignificant(tokens, fnIdx)
                        if (k < tokens.size && tokens[k].kind == TokenKind.IDENTIFIER) {
                            k = nextSignificant(tokens, k)
                        }
                        if (k < tokens.size && tokens[k].text == "(") {
                            val paramsEnd = matchParen(tokens, k)
                            if (paramsEnd >= 0) {
                                val bodyBrace = nextSignificant(tokens, paramsEnd)
                                if (bodyBrace < tokens.size && tokens[bodyBrace].text == "{") {
                                    out.append("function")
                                    for (m in fnIdx + 1 until bodyBrace) {
                                        out.append(tokens[m].raw)
                                    }
                                    out.append("{ return __operitRunGen(function*() ")
                                    stack.add(BraceKind.GENERATOR_BODY)
                                    i = bodyBrace + 1
                                    continue
                                }
                            }
                        }
                        out.append(t.raw)
                        i++
                    } else if (nextIdx < tokens.size && tokens[nextIdx].text == "(") {
                        val paramsEnd = matchParen(tokens, nextIdx)
                        val arrowIdx = nextSignificant(tokens, paramsEnd)
                        if (arrowIdx < tokens.size && tokens[arrowIdx].text == "=>") {
                            val bodyIdx = nextSignificant(tokens, arrowIdx)
                            out.append(" (")
                            for (m in nextIdx + 1 until paramsEnd) out.append(tokens[m].raw)
                            if (bodyIdx < tokens.size && tokens[bodyIdx].text == "{") {
                                val bodyEnd = matchBrace(tokens, bodyIdx)
                                out.append(") => { return __operitRunGen(function*() ")
                                copyRangeTranspiled(tokens, bodyIdx + 1, bodyEnd, out, true)
                                out.append("); }")
                                i = bodyEnd + 1
                            } else {
                                val exprEnd = findExpressionEnd(tokens, bodyIdx)
                                out.append(") => __operitRunGen(function*() { return ")
                                copyRangeTranspiled(tokens, bodyIdx, exprEnd, out, true)
                                out.append("; })")
                                i = exprEnd + 1
                            }
                            continue
                        }
                        out.append(t.raw)
                        i++
                    } else if (nextIdx < tokens.size && tokens[nextIdx].kind == TokenKind.IDENTIFIER) {
                        val arrowIdx = nextSignificant(tokens, nextIdx)
                        if (arrowIdx < tokens.size && tokens[arrowIdx].text == "=>") {
                            val bodyIdx = nextSignificant(tokens, arrowIdx)
                            out.append(" ")
                            out.append(tokens[nextIdx].raw)
                            if (bodyIdx < tokens.size && tokens[bodyIdx].text == "{") {
                                val bodyEnd = matchBrace(tokens, bodyIdx)
                                out.append(" => { return __operitRunGen(function*() ")
                                copyRangeTranspiled(tokens, bodyIdx + 1, bodyEnd, out, true)
                                out.append("); }")
                                i = bodyEnd + 1
                            } else {
                                val exprEnd = findExpressionEnd(tokens, bodyIdx)
                                out.append(" => __operitRunGen(function*() { return ")
                                copyRangeTranspiled(tokens, bodyIdx, exprEnd, out, true)
                                out.append("; })")
                                i = exprEnd + 1
                            }
                            continue
                        }
                        // 对象/类方法：async foo(params) { ... }
                        val k = nextSignificant(tokens, nextIdx)
                        if (k < tokens.size && tokens[k].text == "(") {
                            val paramsEnd = matchParen(tokens, k)
                            if (paramsEnd >= 0) {
                                val bodyBrace = nextSignificant(tokens, paramsEnd)
                                if (bodyBrace < tokens.size && tokens[bodyBrace].text == "{") {
                                    out.append(" ")
                                    out.append(tokens[nextIdx].raw)
                                    for (m in k until bodyBrace) out.append(tokens[m].raw)
                                    out.append("{ return __operitRunGen(function*() ")
                                    stack.add(BraceKind.GENERATOR_BODY)
                                    i = bodyBrace + 1
                                    continue
                                }
                            }
                        }
                        out.append(t.raw)
                        i++
                    } else {
                        out.append(t.raw)
                        i++
                    }
                }

                t.text == "await" && !t.inString && stack.any { it == BraceKind.GENERATOR_BODY } -> {
                    out.append("yield")
                    i++
                }

                t.text == "{" && !t.inString -> {
                    stack.add(BraceKind.NORMAL)
                    out.append(t.raw)
                    i++
                }

                t.text == "}" && !t.inString && stack.isNotEmpty() -> {
                    when (stack.removeAt(stack.size - 1)) {
                        BraceKind.GENERATOR_BODY -> out.append("); }")
                        BraceKind.NORMAL -> out.append("}")
                    }
                    i++
                }

                else -> {
                    out.append(t.raw)
                    i++
                }
            }
        }
        return out.toString()
    }

    private enum class BraceKind { NORMAL, GENERATOR_BODY }

    private fun copyRangeTranspiled(
        tokens: List<Token>,
        from: Int,
        to: Int,
        out: StringBuilder,
        transpileAwait: Boolean,
    ) {
        if (from > to) return
        for (idx in from..to) {
            val tt = tokens[idx]
            if (transpileAwait && tt.text == "await" && !tt.inString) {
                out.append("yield")
            } else {
                out.append(tt.raw)
            }
        }
    }

    private fun findExpressionEnd(tokens: List<Token>, from: Int): Int {
        var depth = 0
        for (i in from until tokens.size) {
            val t = tokens[i]
            if (t.inString) continue
            when (t.text) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> {
                    if (depth == 0) return i - 1
                    depth--
                }
                ";", ",", "=>" -> if (depth == 0) return i - 1
            }
        }
        return tokens.size - 1
    }

    private fun nextSignificant(tokens: List<Token>, from: Int): Int {
        var i = from + 1
        while (i < tokens.size && tokens[i].isTrivia) i++
        return i
    }

    private fun matchParen(tokens: List<Token>, openIdx: Int): Int {
        if (tokens[openIdx].text != "(") return -1
        var depth = 0
        for (i in openIdx until tokens.size) {
            val t = tokens[i]
            if (t.inString) continue
            when (t.text) {
                "(" -> depth++
                ")" -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun matchBrace(tokens: List<Token>, openIdx: Int): Int {
        if (tokens[openIdx].text != "{") return -1
        var depth = 0
        for (i in openIdx until tokens.size) {
            val t = tokens[i]
            if (t.inString) continue
            when (t.text) {
                "{" -> depth++
                "}" -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    internal enum class TokenKind { WHITESPACE, COMMENT, STRING, TEMPLATE, REGEX, NUMBER, IDENTIFIER, PUNCT }

    internal data class Token(
        val kind: TokenKind,
        val text: String,
        val raw: String,
        val inString: Boolean,
    ) {
        val isTrivia: Boolean
            get() = kind == TokenKind.WHITESPACE || kind == TokenKind.COMMENT
    }

    internal fun tokenize(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val n = source.length
        var i = 0
        var prevSignificant: String? = null
        while (i < n) {
            val c = source[i]
            when {
                c.isWhitespace() -> {
                    val start = i
                    while (i < n && source[i].isWhitespace()) i++
                    tokens.add(Token(TokenKind.WHITESPACE, source.substring(start, i), source.substring(start, i), false))
                }

                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    val start = i
                    i += 2
                    while (i < n && source[i] != '\n') i++
                    tokens.add(Token(TokenKind.COMMENT, source.substring(start, i), source.substring(start, i), false))
                }

                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    while (i < n && !(source[i] == '*' && i + 1 < n && source[i + 1] == '/')) i++
                    i = minOf(n, i + 2)
                    tokens.add(Token(TokenKind.COMMENT, source.substring(start, i), source.substring(start, i), false))
                }

                (c == '\'' || c == '"') -> {
                    val start = i
                    val quote = c
                    i++
                    while (i < n) {
                        if (source[i] == '\\') { i += 2; continue }
                        if (source[i] == quote) { i++; break }
                        i++
                    }
                    val raw = source.substring(start, i)
                    tokens.add(Token(TokenKind.STRING, raw, raw, true))
                }

                c == '`' -> {
                    val start = i
                    i++
                    while (i < n) {
                        if (source[i] == '\\') { i += 2; continue }
                        if (source[i] == '`') { i++; break }
                        i++
                    }
                    val raw = source.substring(start, i)
                    tokens.add(Token(TokenKind.TEMPLATE, raw, raw, true))
                }

                c == '/' && isRegexStart(prevSignificant) -> {
                    val start = i
                    i++
                    var inClass = false
                    while (i < n) {
                        val ch = source[i]
                        if (ch == '\\') { i += 2; continue }
                        if (ch == '[') inClass = true
                        else if (ch == ']') inClass = false
                        else if (ch == '/' && !inClass) { i++; break }
                        else if (ch == '\n') break
                        i++
                    }
                    while (i < n && source[i] in "gimsuy") i++
                    val raw = source.substring(start, i)
                    tokens.add(Token(TokenKind.REGEX, raw, raw, false))
                }

                c.isDigit() || (c == '.' && i + 1 < n && source[i + 1].isDigit()) -> {
                    val start = i
                    while (i < n) {
                        val ch = source[i]
                        when {
                            ch.isDigit() || ch == '.' -> i++
                            ch == 'e' || ch == 'E' -> {
                                i++
                                if (i < n && (source[i] == '+' || source[i] == '-')) i++
                            }
                            ch == 'x' || ch == 'X' -> {
                                i++
                                while (i < n && source[i].isHexDigit()) i++
                            }
                            ch == '_' && i > start -> i++
                            else -> break
                        }
                    }
                    val raw = source.substring(start, i)
                    tokens.add(Token(TokenKind.NUMBER, raw, raw, false))
                }

                c == '_' || c == '$' || c.isLetter() || c == '\\' -> {
                    val start = i
                    if (c == '\\') {
                        i++
                        if (i < n && source[i] == 'u') i++
                    }
                    while (i < n) {
                        val ch = source[i]
                        if (ch == '_' || ch == '$' || ch.isLetterOrDigit()) i++
                        else break
                    }
                    val raw = source.substring(start, i)
                    tokens.add(Token(TokenKind.IDENTIFIER, raw, raw, false))
                }

                c == '=' && i + 1 < n && source[i + 1] == '>' -> {
                    tokens.add(Token(TokenKind.PUNCT, "=>", "=>", false))
                    i += 2
                }

                else -> {
                    val raw = source.substring(i, i + 1)
                    tokens.add(Token(TokenKind.PUNCT, raw, raw, false))
                    i++
                }
            }
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                prevSignificant = c.toString()
            }
        }
        return tokens
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** 判断 `/` 是否开启正则字面量：前一个显著 token 决定 */
    private fun isRegexStart(prev: String?): Boolean {
        if (prev == null) return true
        if (prev.length != 1) return false
        return when (prev[0]) {
            ')', ']', '}', '"', '\'', '`' -> false
            else -> !(prev[0].isLetterOrDigit() || prev[0] == '_' || prev[0] == '$')
        }
    }
}
