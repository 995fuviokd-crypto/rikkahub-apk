package me.rerere.ai.provider

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Socks4SocketFactoryTest {

    private lateinit var proxyServer: ServerSocket
    private var proxyThread: Thread? = null
    private val receivedRequest = AtomicReference<ByteArray?>(null)

    @Before
    fun setUp() {
        proxyServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        proxyThread = thread {
            runCatching {
                proxyServer.accept().use { client ->
                    val din = DataInputStream(client.getInputStream())
                    // read 9 bytes header: VN CD DSTPORT(2) DSTIP(4) USERID(0 or until \0)
                    val header = ByteArray(8)
                    din.readFully(header)
                    var userIdLen = 0
                    var b = din.readUnsignedByte()
                    val userIdBytes = mutableListOf<Int>()
                    while (b != 0) {
                        userIdBytes.add(b)
                        b = din.readUnsignedByte()
                    }
                    val full = header + byteArrayOf(*userIdBytes.map { it.toByte() }.toByteArray(), 0)
                    receivedRequest.set(full)
                    // reply: VN=0 CD=90 DSTPORT(2) DSTIP(4)
                    val dout = DataOutputStream(client.getOutputStream())
                    dout.write(byteArrayOf(0x00, 90.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                    dout.flush()
                }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { proxyServer.close() }
        proxyThread?.join(2000)
    }

    @Test
    fun `socks4 handshake sends correct CONNECT request`() = runBlocking {
        val port = proxyServer.localPort
        val factory = Socks4SocketFactory("127.0.0.1", port, userId = "testUser")

        withContext(Dispatchers.IO) {
            factory.createSocket("93.184.216.34", 443)
                .use { socket ->
                    assertEquals(port, socket.port)
                }
        }

        val req = receivedRequest.get()
            ?: throw AssertionError("proxy server did not receive a request")
        // VN=4, CD=1
        assertEquals(0x04, req[0].toInt() and 0xFF)
        assertEquals(0x01, req[1].toInt() and 0xFF)
        // DSTPORT = 443 (0x01BB)
        assertEquals(0x01, req[2].toInt() and 0xFF)
        assertEquals(0xBB, req[3].toInt() and 0xFF)
        // DSTIP = 93.184.216.34
        assertArrayEquals(
            byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
            req.copyOfRange(4, 8),
        )
        // USERID = "testUser" + \0
        val userIdPart = req.copyOfRange(8, req.size - 1).toString(Charsets.UTF_8)
        assertEquals("testUser", userIdPart)
        assertEquals(0x00, req[req.size - 1].toInt() and 0xFF)
    }

    @Test
    fun `socks4 rejects non-granted reply`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread {
            runCatching {
                server.accept().use { client ->
                    val din = DataInputStream(client.getInputStream())
                    val header = ByteArray(8)
                    din.readFully(header)
                    var b = din.readUnsignedByte()
                    while (b != 0) {
                        b = din.readUnsignedByte()
                    }
                    val dout = DataOutputStream(client.getOutputStream())
                    // CD=91 (request rejected)
                    dout.write(byteArrayOf(0x00, 91.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                    dout.flush()
                }
            }
        }
        try {
            val factory = Socks4SocketFactory("127.0.0.1", server.localPort)
            val thrown = runCatching {
                withContext(Dispatchers.IO) {
                    factory.createSocket("93.184.216.34", 443).use { }
                }
            }.exceptionOrNull()
            assertTrue("expected IOException, got ${thrown?.message}", thrown is java.io.IOException)
        } finally {
            server.close()
        }
    }

    @Test
    fun `proxy config direct maps to null okhttp proxy`() {
        val direct = ProxyConfig(enabled = true, type = ProxyType.DIRECT, host = "x", port = 1)
        assertEquals(null, direct.toOkHttpProxy())
        assertTrue(direct.isDirect)
    }

    @Test
    fun `http https socks4 socks5 map to correct okhttp proxy types`() {
        assertEquals(
            java.net.Proxy.Type.HTTP,
            ProxyConfig(enabled = true, type = ProxyType.HTTP, host = "h", port = 80).toOkHttpProxy()!!.type()
        )
        assertEquals(
            java.net.Proxy.Type.HTTP,
            ProxyConfig(enabled = true, type = ProxyType.HTTPS, host = "h", port = 443).toOkHttpProxy()!!.type()
        )
        assertEquals(
            java.net.Proxy.Type.SOCKS,
            ProxyConfig(enabled = true, type = ProxyType.SOCKS5, host = "h", port = 1080).toOkHttpProxy()!!.type()
        )
        // SOCKS4 无法表示为 OkHttp Proxy，返回 null（由 socketFactory 处理）
        assertEquals(
            null,
            ProxyConfig(enabled = true, type = ProxyType.SOCKS4, host = "h", port = 1080).toOkHttpProxy()
        )
    }
}
