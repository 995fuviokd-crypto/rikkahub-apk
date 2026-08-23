package me.rerere.ai.provider

import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 网络代理配置。支持 HTTP / HTTPS / SOCKS4 / SOCKS5 代理与直连，认证可选。
 * 可挂在 [ProviderSetting] 上作为单个 Provider 的代理，也可挂在全局设置上作为兜底代理。
 */
@Serializable
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    /** 是否已配置且可用的代理 */
    val isConfigured: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    /** 是否使用直连（忽略全局代理） */
    val isDirect: Boolean
        get() = type == ProxyType.DIRECT

    /** 缓存的唯一键，用于派生 OkHttpClient 复用 */
    val cacheKey: String
        get() = "${type.name}://$host:$port:${username.takeLast(4)}"

    /** 转为 OkHttp Proxy，未配置时返回 null */
    fun toOkHttpProxy(): Proxy? {
        if (!isConfigured || isDirect) return null
        val type = when (this.type) {
            ProxyType.HTTP, ProxyType.HTTPS -> Proxy.Type.HTTP
            ProxyType.SOCKS5 -> Proxy.Type.SOCKS
            ProxyType.SOCKS4, ProxyType.DIRECT -> null
        } ?: return null
        return Proxy(type, InetSocketAddress(host, port))
    }
}

@Serializable
enum class ProxyType {
    @SerialName("http")
    HTTP,

    @SerialName("https")
    HTTPS,

    @SerialName("socks4")
    SOCKS4,

    @SerialName("socks5")
    SOCKS5,

    @SerialName("direct")
    DIRECT,
}
