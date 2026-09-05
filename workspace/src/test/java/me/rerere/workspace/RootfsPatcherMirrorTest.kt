package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * apt/apk 镜像源重写规则测试:
 * - Ubuntu arm64 源必须路由到 TUNA ubuntu-ports 档案(ubuntu/ 只含 amd64/i386, 走错必 404)
 * - EOL 版本(如 questing 25.10)切换到 ubuntu-old-releases 并丢弃 pocket 套件
 * - 修复历史误 patch(arm64 曾被写到 /ubuntu/), 用户自配镜像不动
 */
class RootfsPatcherMirrorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val portsTuna = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/"
    private val oldReleasesBase = "https://old-releases.ubuntu.com/ubuntu/"

    private fun rootfs(machine: Int? = EM_AARCH64, caCerts: Boolean = true): File {
        val dir = tmp.newFolder()
        File(dir, "etc/apt/sources.list.d").mkdirs()
        machine?.let { writeElf(dir, "bin/sh", it) }
        if (caCerts) {
            File(dir, "usr/share/ca-certificates").mkdirs()
            File(dir, "etc/ssl/certs").mkdirs()
            File(dir, "etc/ssl/certs/ca-certificates.crt").writeText("fake-ca")
        }
        return dir
    }

    private fun writeElf(dir: File, rel: String, machine: Int) {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        val h = ByteArray(64)
        h[0] = 0x7f
        h[1] = 0x45
        h[2] = 0x4c
        h[3] = 0x46
        h[4] = 2 // ELFCLASS64
        h[5] = 1 // ELFDATA2LSB
        h[16] = 2 // ET_EXEC
        h[18] = (machine and 0xff).toByte()
        h[19] = ((machine shr 8) and 0xff).toByte()
        f.writeBytes(h)
    }

    @Test
    fun `deb822 arm64 ports ubuntu routes to tuna ubuntu-ports`() {
        val dir = rootfs()
        val src = File(dir, "etc/apt/sources.list.d/ubuntu.sources")
        src.writeText(
            """
            Types: deb
            URIs: http://ports.ubuntu.com/ubuntu-ports/
            Suites: noble noble-updates noble-backports
            Components: main universe
            Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

            """.trimIndent(),
        )
        RootfsPatcher().patch(dir)
        val text = src.readText()
        assertTrue(text.contains("URIs: $portsTuna"))
        assertTrue(text.contains("Suites: noble noble-updates noble-backports"))
        assertFalse(text.contains("ports.ubuntu.com"))
    }

    @Test
    fun `one-line arm64 ports ubuntu routes to tuna ubuntu-ports`() {
        val dir = rootfs()
        val list = File(dir, "etc/apt/sources.list")
        list.writeText(
            "deb http://ports.ubuntu.com/ubuntu-ports/ noble main universe\n" +
                "deb-src http://ports.ubuntu.com/ubuntu-ports/ noble main\n",
        )
        RootfsPatcher().patch(dir)
        assertEquals(
            "deb $portsTuna noble main universe\n" +
                "deb-src $portsTuna noble main\n",
            list.readText(),
        )
    }

    @Test
    fun `repairs previously mispatched tuna ubuntu archive on arm64`() {
        // 历史版本曾把 arm64 源误写到 amd64 档案, 必须能自愈
        val dir = rootfs()
        val src = File(dir, "etc/apt/sources.list.d/ubuntu.sources")
        src.writeText("URIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu/\nSuites: noble noble-updates\n")
        RootfsPatcher().patch(dir)
        val text = src.readText()
        assertTrue(text.contains("URIs: $portsTuna"))
        assertFalse(text.contains("ubuntu/\n"))
    }

    @Test
    fun `eol oracular deb822 switches to old-releases and drops pockets`() {
        val dir = rootfs()
        val src = File(dir, "etc/apt/sources.list.d/ubuntu.sources")
        src.writeText(
            """
            Types: deb
            URIs: http://ports.ubuntu.com/ubuntu-ports/
            Suites: oracular oracular-security oracular-updates
            Components: main universe

            """.trimIndent(),
        )
        RootfsPatcher().patch(dir)
        val text = src.readText()
        assertTrue(text.contains("URIs: $oldReleasesBase"))
        assertTrue(text.contains("Suites: oracular"))
        assertFalse(text.contains("oracular-security"))
        assertFalse(text.contains("oracular-updates"))
    }

    @Test
    fun `eol oracular one-line comments out pockets and rewrites base suite`() {
        val dir = rootfs()
        val list = File(dir, "etc/apt/sources.list")
        list.writeText(
            "deb http://ports.ubuntu.com/ubuntu-ports/ oracular main universe\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports/ oracular-updates main\n",
        )
        RootfsPatcher().patch(dir)
        val text = list.readText()
        assertTrue(text.contains("deb $oldReleasesBase oracular main universe"))
        assertTrue(text.contains("# deb http://ports.ubuntu.com/ubuntu-ports/ oracular-updates main"))
    }

    @Test
    fun `x86_64 archive ubuntu stays on amd64 archive`() {
        val dir = rootfs(machine = EM_X86_64)
        val list = File(dir, "etc/apt/sources.list")
        list.writeText("deb http://archive.ubuntu.com/ubuntu/ noble main\n")
        RootfsPatcher().patch(dir)
        assertEquals(
            "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ noble main\n",
            list.readText(),
        )
    }

    @Test
    fun `debian official host routes to tuna debian`() {
        val dir = rootfs()
        val list = File(dir, "etc/apt/sources.list")
        list.writeText("deb http://deb.debian.org/debian bookworm main\n")
        RootfsPatcher().patch(dir)
        assertEquals(
            "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bookworm main\n",
            list.readText(),
        )
    }

    @Test
    fun `user configured mirror is left untouched`() {
        val dir = rootfs()
        val list = File(dir, "etc/apt/sources.list")
        val original = "deb https://mirrors.aliyun.com/ubuntu/ noble main\n"
        list.writeText(original)
        RootfsPatcher().patch(dir)
        assertEquals(original, list.readText())
    }

    @Test
    fun `stale resolv conf with dead private nameserver is rewritten`() {
        // 镜像自带的内网 DNS 已失效; 旧逻辑见非本地 nameserver 就跳过 → DNS 永久不可用
        val dir = rootfs(machine = null)
        File(dir, "etc/resolv.conf").writeText("nameserver 10.0.2.3\n")
        RootfsPatcher().patch(dir)
        val text = File(dir, "etc/resolv.conf").readText()
        assertTrue(text.contains("nameserver 223.5.5.5"))
        assertFalse(text.contains("10.0.2.3"))
    }

    @Test
    fun `alpine repositories route to aliyun`() {
        val dir = rootfs(machine = null)
        val apkDir = File(dir, "etc/apk").apply { mkdirs() }
        val repos = File(apkDir, "repositories")
        repos.writeText(
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.22/community\n",
        )
        RootfsPatcher().patch(dir)
        assertEquals(
            "https://mirrors.aliyun.com/alpine/v3.22/main\n" +
                "https://mirrors.aliyun.com/alpine/v3.22/community\n",
            repos.readText(),
        )
    }

    @Test
    fun `without ca-certificates mirrors fall back to http scheme`() {
        // ubuntu-base 最小镜像无 ca-certificates, https 镜像会证书校验失败
        val dir = rootfs(caCerts = false)
        val list = File(dir, "etc/apt/sources.list")
        list.writeText("deb http://ports.ubuntu.com/ubuntu-ports/ noble main\n")
        RootfsPatcher().patch(dir)
        assertEquals(
            "deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main\n",
            list.readText(),
        )
    }

    @Test
    fun `patch is idempotent`() {
        val dir = rootfs()
        val src = File(dir, "etc/apt/sources.list.d/ubuntu.sources")
        src.writeText("URIs: http://ports.ubuntu.com/ubuntu-ports/\nSuites: noble noble-updates\n")
        RootfsPatcher().patch(dir)
        val first = src.readText()
        RootfsPatcher().patch(dir)
        assertEquals(first, src.readText())
    }

    private companion object {
        private const val EM_AARCH64 = 183
        private const val EM_X86_64 = 62
    }
}
