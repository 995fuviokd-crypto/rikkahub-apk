package me.rerere.rikkahub.data.ai.agent

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 验证自写的轻量 tar.gz 解压器(Node 离线内置) 能正确解压真实 Node tarball:
 * 普通文件(node 二进制)、目录、软链均被正确还原。
 */
class NodeTarExtractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun extractRealNodeTarball() {
        val tarPath = "/tar/node-test.tar.gz"
        val input = requireNotNull(javaClass.getResourceAsStream(tarPath)) { "missing $tarPath" }
        val dest = tmp.newFolder("node")
        extractTarGz(BufferedInputStream(input), dest)

        // node 可执行文件存在且可执行
        val nodeBin = File(dest, "bin/node")
        assertTrue("bin/node 缺失", nodeBin.isFile)
        assertTrue("bin/node 应可执行", nodeBin.canExecute())

        // 软链还原(如 lib 下某些 so 的软链、bin/npm 等)
        val npmBin = File(dest, "bin/npm")
        assertTrue(
            "bin/npm 应为软链(实际存在=$npmBin.exists(), 软链=" + java.nio.file.Files.isSymbolicLink(npmBin.toPath()) + ")",
            java.nio.file.Files.isSymbolicLink(npmBin.toPath()),
        )
        // 软链目标最终可达(bin/npm -> ../lib/node_modules/npm/bin/npm-cli.js)
        val resolvedNpm = java.nio.file.Files.readSymbolicLink(npmBin.toPath())
        assertTrue("bin/npm 目标解析失败", java.nio.file.Files.exists(npmBin.toPath()))

        // 目录结构: lib 下应有大量模块文件
        val libDir = File(dest, "lib")
        assertTrue("lib 目录缺失", libDir.isDirectory)
        assertTrue("lib 目录为空", (libDir.listFiles() ?: emptyArray()).isNotEmpty())

        // 版本文件
        val versionTxt = File(dest, "lib/node_modules/npm/package.json")
        assertTrue("npm package.json 缺失", versionTxt.isFile)
    }

    private fun extractTarGz(input: java.io.InputStream, destDir: File) {
        GZIPInputStream(input).use { gz ->
            val tar = BufferedInputStream(gz)
            val header = ByteArray(512)
            var pendingLongName: String? = null
            while (true) {
                val read = readFully(tar, header)
                if (read == -1 || read == 0) break
                if (header.all { it == 0.toByte() }) break
                val type = header[156].toInt().toChar()
                val size = parseOctal(header, 124, 12) ?: 0
                if (type == 'L') {
                    pendingLongName = readDataString(tar, size)
                    continue
                }
                var name = parseTarString(header, 0, 100)
                pendingLongName?.let { long ->
                    name = long
                    pendingLongName = null
                }
                val mode = parseOctal(header, 100, 8) ?: 0
                if (name.isEmpty()) {
                    skipAligned(tar, size)
                    continue
                }
                val cleanName = name.removePrefix("./")
                val prefix = parseTarString(header, 345, 155)
                val rawName = if (prefix.isNotEmpty() && !cleanName.startsWith(prefix)) "$prefix/$cleanName" else cleanName
                val stripped = rawName.split('/').drop(1).joinToString("/")
                if (stripped.isEmpty()) {
                    skipAligned(tar, size)
                    continue
                }
                val fullTarget = File(destDir, stripped).normalize()
                val parent = fullTarget.parentFile
                if (parent != null) parent.mkdirs()
                when (type) {
                    '5' -> fullTarget.mkdirs()
                    '2' -> {
                        val linkTarget = parseTarString(header, 157, 100)
                        try {
                            java.nio.file.Files.createSymbolicLink(
                                fullTarget.toPath(),
                                java.nio.file.Paths.get(linkTarget),
                            )
                        } catch (e: Exception) {
                            // 忽略
                        }
                    }
                    '0', '\u0000' -> {
                        FileOutputStream(fullTarget).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var remaining = size
                            while (remaining > 0) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val n = tar.read(buf, 0, toRead)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        if (mode and 0x40L != 0L) fullTarget.setExecutable(true, false)
                        skipPad(tar, size)
                    }
                    else -> skipAligned(tar, size)
                }
            }
        }
    }

    private fun readDataString(tar: java.io.InputStream, size: Long): String {
        val buf = ByteArray(size.toInt().coerceAtLeast(0))
        var off = 0
        while (off < buf.size) {
            val n = tar.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) skipFully(tar, pad)
        return String(buf, 0, off, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun skipPad(input: java.io.InputStream, size: Long) {
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) skipFully(input, pad)
    }

    private fun skipFully(input: java.io.InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun parseTarString(h: ByteArray, offset: Int, len: Int): String {
        val end = (offset until offset + len).firstOrNull { h[it] == 0.toByte() } ?: (offset + len)
        return String(h, offset, end - offset, Charsets.US_ASCII)
    }

    private fun parseOctal(h: ByteArray, offset: Int, len: Int): Long? {
        var v = 0L
        var started = false
        for (i in offset until offset + len) {
            val c = h[i].toInt().toChar()
            if (c == '\u0000' || c == ' ') {
                if (started) break else continue
            }
            if (c !in '0'..'7') return null
            started = true
            v = v * 8 + (c - '0')
        }
        return if (started) v else null
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        var n: Int
        while (total < buf.size) {
            n = input.read(buf, total, buf.size - total)
            if (n == -1) break
            total += n
        }
        return if (total == 0) -1 else total
    }

    private fun skipAligned(input: java.io.InputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) skipFully(input, pad)
    }
}
