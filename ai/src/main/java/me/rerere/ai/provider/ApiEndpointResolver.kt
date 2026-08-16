package me.rerere.ai.provider

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * API Base URL 解析器：根据用户填写的 Base URL 自动推导实际请求地址。
 *
 * 规则：
 * - 用户填写域名根地址（无路径或以 / 结尾）时，自动补充 `/v1` 前缀
 * - 若 Base URL 以 `@` 结尾，表示用户已提供完整端点地址，禁用自动补充：
 *   `@` 会被剥离，请求将直接发送到填写地址，不再拼接任何路径
 */
object ApiEndpointResolver {
    /**
     * Base URL 是否为显式完整端点（以 `@` 结尾）。
     * 此时该地址将被原样用作所有请求的目标地址。
     */
    fun isExplicitBaseUrl(input: String): Boolean =
        input.trim().endsWith("@")

    /**
     * 推导实际 Base URL：
     * - 去掉末尾 `@`（显式模式）
     * - 去掉末尾 `/`
     * - 自动模式下，若路径为空或以 `/` 结尾，则补充 `/v1`
     */
    fun resolveBaseUrl(input: String): String {
        val trimmed = input.trim()
        val explicit = trimmed.endsWith("@")
        val raw = if (explicit) trimmed.dropLast(1).trim() else trimmed
        val noTrailingSlash = raw.trimEnd('/')
        if (explicit || noTrailingSlash.isBlank()) return noTrailingSlash

        val url = noTrailingSlash.toHttpUrlOrNull() ?: return noTrailingSlash
        val path = url.encodedPath
        if (path.isNullOrEmpty() || path == "/") {
            return url.newBuilder().encodedPath("/v1").build().toString()
        }
        return noTrailingSlash
    }

    /**
     * 计算完整端点地址：
     * - 显式模式（`@`）：直接返回推导后的 Base URL（不拼接任何路径）
     * - 自动模式：`Base URL + path`
     */
    fun resolveEndpoint(baseUrlInput: String, path: String): String {
        val base = resolveBaseUrl(baseUrlInput)
        if (base.isBlank()) return base
        if (isExplicitBaseUrl(baseUrlInput)) return base
        return base.trimEnd('/') + path
    }
}
