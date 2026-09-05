package me.rerere.rikkahub.ui.components.ui

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 错误人话化映射：把原始异常/HTTP 状态翻译为用户可理解的摘要与建议动作。
 *
 * 返回 null 表示无法识别（UI 回退展示原始消息首行）。
 * CancellationException 属于用户主动取消，不应进入错误展示。
 */
data class ChatErrorHint(
    val summary: String,
    val suggestion: String? = null,
    val retryable: Boolean = true,
)

object ChatErrorMapper {
    fun classify(t: Throwable): ChatErrorHint? {
        if (t is CancellationException) return null
        val message = t.message ?: t.toString()
        val lower = message.lowercase()

        fun contains(vararg keys: String) = keys.any { lower.contains(it) }

        return when {
            t is UnknownHostException || contains("unable to resolve host", "dns") -> ChatErrorHint(
                summary = "无法解析服务器地址",
                suggestion = "请检查网络连接、Base URL 拼写或代理设置",
            )

            t is SocketTimeoutException || contains("timeout", "timed out") -> ChatErrorHint(
                summary = "请求超时",
                suggestion = "网络不稳定或服务端响应过慢，可重试或稍后再试",
            )

            t is ConnectException || contains("failed to connect", "connection refused", "econnrefused") -> ChatErrorHint(
                summary = "无法连接到服务器",
                suggestion = "请检查网络、代理或服务地址是否可访问",
            )

            t is SSLException || contains("ssl", "certificate", "handshake") -> ChatErrorHint(
                summary = "安全连接失败",
                suggestion = "可能是证书或网络劫持问题，请检查网络环境",
            )

            contains("content-length", "premature", "unexpected end of stream", "eof", "connection reset", "closed connection") -> ChatErrorHint(
                summary = "连接中断",
                suggestion = "生成过程中连接被断开，可点击重试继续",
            )

            contains("401", "unauthorized") -> ChatErrorHint(
                summary = "API 密钥无效或已过期",
                suggestion = "请到模型设置中检查该服务的 API Key",
            )

            contains("403", "forbidden") -> ChatErrorHint(
                summary = "没有访问权限",
                suggestion = "密钥可能无权访问该模型，或账号已被禁用",
            )

            contains("404", "not found") -> ChatErrorHint(
                summary = "接口或模型不存在",
                suggestion = "请检查 Base URL 与模型名称是否正确",
            )

            contains("429", "rate limit", "quota", "insufficient") -> ChatErrorHint(
                summary = "请求过于频繁或额度不足",
                suggestion = "请稍后重试，或检查账号余额/限额",
            )

            contains("400", "bad request") -> ChatErrorHint(
                summary = "请求被拒绝",
                suggestion = "可能是模型不支持当前参数或消息内容过长",
            )

            contains("500") -> ChatErrorHint(
                summary = "服务端内部错误",
                suggestion = "服务方暂时故障，可稍后重试",
            )

            contains("502", "503", "504", "bad gateway", "service unavailable", "overloaded") -> ChatErrorHint(
                summary = "服务暂时不可用",
                suggestion = "服务方负载过高或正在维护，可稍后重试",
            )

            contains("balance", "余额", "arrears", "欠费") -> ChatErrorHint(
                summary = "账户余额不足",
                suggestion = "请到服务商控制台充值后重试",
            )

            else -> null
        }
    }
}
