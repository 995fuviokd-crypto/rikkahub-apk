package me.rerere.ai.provider

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 按 [ProxyConfig] 派生带代理的 [OkHttpClient]。
 *
 * 所有 Provider 共享同一个基础 client；本类为每个代理配置派生一个带
 * `proxy` 的连接层 client 并缓存复用。未配置代理时直接返回基础 client。
 * 派生 client 通过 `base.newBuilder().proxy(...)` 复用基础 client 的
 * 连接池/拦截器/Dispatcher，仅连接层走代理。
 */
class ProviderHttpClient(
    private val base: OkHttpClient,
) {
    private val proxyClients = ConcurrentHashMap<String, OkHttpClient>()

    /**
     * 获取适用于指定代理配置的 client。
     * [proxy] 为 null 或未启用时返回基础 client（由全局 ProxySelector 兜底）。
     */
    fun clientFor(proxy: ProxyConfig?): OkHttpClient {
        if (proxy == null || !proxy.isConfigured) return base
        return proxyClients.computeIfAbsent(proxy.cacheKey) { deriveClient(proxy) }
    }

    private fun deriveClient(proxy: ProxyConfig): OkHttpClient {
        if (proxy.isDirect) {
            // 强制直连，覆盖全局 ProxySelector
            return base.newBuilder().proxy(Proxy.NO_PROXY).build()
        }
        val builder = base.newBuilder()
        if (proxy.type == ProxyType.SOCKS4) {
            // SOCKS4：OkHttp 不支持，用自定义 SocketFactory 完成隧道握手
            builder.proxy(Proxy.NO_PROXY)
                .socketFactory(
                    Socks4SocketFactory(
                        proxyHost = proxy.host,
                        proxyPort = proxy.port,
                        userId = proxy.username,
                    )
                )
        } else {
            builder.proxy(proxy.toOkHttpProxy())
            if (proxy.username.isNotBlank()) {
                builder.proxyAuthenticator(ProxyAuthenticator(proxy))
            }
        }
        return builder.build()
    }

    private class ProxyAuthenticator(
        private val proxy: ProxyConfig,
    ) : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.code != 407) return null
            return response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(proxy.username, proxy.password))
                .build()
        }
    }
}

/**
 * 读取全局代理配置的 [ProxySelector]，在每次连接时动态生效，
 * 无需重建 OkHttpClient。未启用全局代理或全局代理为 SOCKS4/DIRECT
 * 时返回 [Proxy.NO_PROXY]。
 *
 * 注意：全局代理不支持 SOCKS4（需 socketFactory，无法经 ProxySelector 下发），
 * 若历史数据残留 SOCKS4 全局配置将按直连处理。
 */
class GlobalProxySelector(
    private val globalProxyProvider: () -> ProxyConfig?,
) : java.net.ProxySelector() {
    override fun select(uri: java.net.URI): MutableList<Proxy> {
        val proxy = globalProxyProvider()
        val okProxy = proxy?.toOkHttpProxy()
        return mutableListOf(okProxy ?: Proxy.NO_PROXY)
    }

    override fun connectFailed(
        uri: java.net.URI?,
        sa: java.net.SocketAddress?,
        ioe: IOException?,
    ) = Unit
}
