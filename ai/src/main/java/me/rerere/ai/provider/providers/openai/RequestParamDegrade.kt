package me.rerere.ai.provider.providers.openai

import me.rerere.ai.util.isHtmlBody

/**
 * 请求参数降级等级上限。
 * 遇到模型/网关不支持的参数时逐级移除重试，实现"适配所有模型"：
 * 1 = 移除 reasoning 专用参数；2 = 移除 temperature/top_p；3 = 移除 tools；4 = 切换/移除 max_tokens 相关
 */
internal const val MAX_REQUEST_DEGRADE_LEVEL = 4

/** 参数被拒的典型 HTTP 状态码 */
internal val PARAM_REJECT_CODES = setOf(400, 422, 405, 502)

/** 错误体中提示"参数不被接受"的关键词 */
internal val PARAM_REJECT_KEYWORDS = listOf(
    "response_format",
    "max_completion_tokens",
    "max_tokens",
    "reasoning_effort",
    "reasoning",
    "stop",
    "temperature",
    "top_p",
    "tools",
    "tool_calls",
    "unknown parameter",
    "unsupported parameter",
    "unrecognized request argument",
    "unexpected parameter",
    "invalid parameter",
    "is not supported",
    "does not support",
    "not supported for this model",
    "incompatible",
    "extra inputs",
)

/** 请求被模型/网关以"参数不受支持"拒绝（含网关返回 HTML 错误页的情况） */
internal fun isParamIncompatibilityError(code: Int, body: String): Boolean {
    if (code in PARAM_REJECT_CODES && body.isHtmlBody()) return true
    if (code !in PARAM_REJECT_CODES) return false
    val lower = body.lowercase()
    return PARAM_REJECT_KEYWORDS.any { it in lower }
}

/**
 * 当前 API 协议（/responses 或 /chat/completions）不被网关支持时抛出，
 * 由 OpenAIProvider 捕获后回退到另一协议。
 */
internal class ProtocolUnavailableException(message: String) : Exception(message)

/**
 * 判断网关是否直接返回了"端点不存在"的 HTML 错误页（如 404），用于触发协议级回退。
 */
internal fun isProtocolUnavailableError(code: Int, body: String): Boolean =
    code == 404 && body.isHtmlBody()

/** 构造用户可读的错误信息，HTML 错误页转成友好提示 */
internal fun buildRequestError(code: Int, body: String): Exception {
    val detail = if (body.isHtmlBody()) {
        "Server returned an HTML error page (HTTP $code). " +
            "This usually means the request contains parameters the model/gateway doesn't support " +
            "(e.g. tools, response_format, max_tokens, stop). Try disabling tool calls / JSON mode / advanced params."
    } else {
        body.take(500)
    }
    return Exception("Failed to get response: $code $detail")
}
