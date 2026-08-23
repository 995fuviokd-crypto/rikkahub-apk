package me.rerere.ai.provider

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * 支持 SOCKS4 代理的 [SocketFactory]。
 *
 * OkHttp 原生仅支持 HTTP 与 SOCKS5 代理，SOCKS4 需要自行实现握手。
 * 本工厂返回的 socket 在首次 [Socket.connect] 时建立到代理的 TCP 连接，
 * 完成 SOCKS4 CONNECT 握手后，再对 OkHttp 透明地暴露为直连 socket。
 */
class Socks4SocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val userId: String = "",
) : SocketFactory() {
    override fun createSocket(): Socket = Socks4Socket()

    override fun createSocket(host: String, port: Int): Socket = createSocket().also {
        it.connect(InetSocketAddress(host, port))
    }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        createSocket().also {
            it.bind(InetSocketAddress(localHost, localPort))
            it.connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: java.net.InetAddress, port: Int): Socket = createSocket().also {
        it.connect(InetSocketAddress(host, port))
    }

    override fun createSocket(
        address: java.net.InetAddress,
        port: Int,
        localAddress: java.net.InetAddress,
        localPort: Int,
    ): Socket = createSocket().also {
        it.bind(InetSocketAddress(localAddress, localPort))
        it.connect(InetSocketAddress(address, port))
    }

    private inner class Socks4Socket : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as? InetSocketAddress
                ?: throw IOException("Unsupported address: $endpoint")

            // 1. connect to the proxy server
            val proxyAddr = InetSocketAddress(proxyHost, proxyPort)
            super.connect(proxyAddr, timeout)
            soTimeout = if (timeout > 0) timeout else 0

            // 2. SOCKS4 CONNECT handshake
            //   VN=4, CD=1, DSTPORT(2B big-endian), DSTIP(4B), USERID(null-terminated)
            val targetIp = target.address
                ?: throw IOException("Unresolved target address: $target")
            val ipBytes = targetIp.address
            if (ipBytes.size != 4) {
                close()
                throw IOException("SOCKS4 only supports IPv4 targets: $target")
            }

            val out = getOutputStream()
            val port = target.port
            out.write(byteArrayOf(0x04, 0x01))
            out.write((port shr 8) and 0xFF)
            out.write(port and 0xFF)
            out.write(ipBytes)
            out.write(userId.toByteArray(Charsets.UTF_8))
            out.write(0x00)
            out.flush()

            // 3. read reply: VN(1B), CD(1B), DSTPORT(2B), DSTIP(4B)
            val din = DataInputStream(getInputStream())
            val vn = din.readUnsignedByte()
            val cd = din.readUnsignedByte()
            din.skipBytes(6)
            if (vn != 0) {
                close()
                throw IOException("SOCKS4 handshake failed: VN=$vn (expect 0)")
            }
            if (cd != 90) {
                close()
                throw IOException("SOCKS4 connect failed: CD=$cd (90 = granted)")
            }
        }
    }
}
