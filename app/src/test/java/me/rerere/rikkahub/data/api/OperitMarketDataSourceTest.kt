package me.rerere.rikkahub.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream

class OperitMarketDataSourceTest {

    @Test
    fun `parseGitHubUrl handles root tree and blob links`() {
        val root = OperitMarketDataSource.parseGitHubUrl("https://github.com/exa-labs/exa-mcp-server")
        assertEquals("exa-labs", root?.owner)
        assertEquals("exa-mcp-server", root?.repo)
        assertEquals("main", root?.ref)
        assertEquals(null, root?.path)

        val tree = OperitMarketDataSource.parseGitHubUrl(
            "https://github.com/Luck-104n/operit-pack/tree/main/skills/ai-experience-notes-v2"
        )
        assertEquals("Luck-104n", tree?.owner)
        assertEquals("operit-pack", tree?.repo)
        assertEquals("main", tree?.ref)
        assertEquals("skills/ai-experience-notes-v2", tree?.path)

        val blob = OperitMarketDataSource.parseGitHubUrl(
            "https://github.com/Luck-104n/operit-pack/blob/main/skills/ai-experience-notes/SKILL.md"
        )
        assertEquals("skills/ai-experience-notes", blob?.path)

        assertEquals(null, OperitMarketDataSource.parseGitHubUrl("https://example.com/x/y"))
    }

    @Test
    fun `extractTarGz unpacks files and directories`() {
        val tar = tarGz(
            files = listOf(
                "myrepo/SKILL.md" to "# skill".toByteArray(),
                "myrepo/skills/a/SKILL.md" to "# nested".toByteArray(),
                "myrepo/mcp.json" to """{"mcpServers":{}}""".toByteArray(),
            ),
            dirs = listOf("myrepo/skills/a"),
        )
        val target = kotlin.io.path.createTempDirectory("tar-test").toFile()
        try {
            OperitMarketDataSource.extractTarGz(tar, target)
            assertTrue(File(target, "myrepo/SKILL.md").readText().contains("# skill"))
            assertTrue(File(target, "myrepo/skills/a/SKILL.md").readText().contains("# nested"))
            assertTrue(File(target, "myrepo/mcp.json").exists())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `zipDirectory packages directory into zip`() {
        val dir = kotlin.io.path.createTempDirectory("zip-test").toFile()
        try {
            File(dir, "plugin.json").writeText("{}")
            File(dir, "SKILL.md").writeText("# x")
            val zip = OperitMarketDataSource.zipDirectory(dir)
            val names = mutableListOf<String>()
            ZipInputStream(zip.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    names.add(e.name)
                    z.closeEntry()
                    e = z.nextEntry
                }
            }
            assertTrue(names.contains("plugin.json"))
            assertTrue(names.contains("SKILL.md"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `locateRoot finds repo prefixed dir`() {
        val dir = kotlin.io.path.createTempDirectory("root-test").toFile()
        try {
            File(dir, "operit-pack-main").mkdirs()
            File(dir, "other").mkdirs()
            val root = OperitMarketDataSource.locateRoot(dir, "operit-pack")
            assertEquals("operit-pack-main", root.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `type label maps operit types`() {
        assertEquals("技能", OperitMarketDataSource.typeLabel("skill"))
        assertEquals("MCP", OperitMarketDataSource.typeLabel("mcp"))
        assertEquals("脚本", OperitMarketDataSource.typeLabel("script"))
        assertEquals("全部", OperitMarketDataSource.typeLabel("all"))
    }

    // ---- helpers ----

    private fun tarGz(
        files: List<Pair<String, ByteArray>>,
        dirs: List<String> = emptyList(),
    ): ByteArray {
        val tar = ByteArrayOutputStream()
        dirs.forEach { tar.write(tarEntry(it, null)) }
        files.forEach { (name, content) -> tar.write(tarEntry(name, content)) }
        tar.write(ByteArray(1024))
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(tar.toByteArray()) }
        return bos.toByteArray()
    }

    private fun tarEntry(name: String, content: ByteArray?): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0, 0, minOf(100, name.length))
        val mode = if (content == null) "040755" else "0100644"
        mode.forEachIndexed { i, c -> header[100 + i] = c.code.toByte() }
        val size = content?.size?.toLong() ?: 0
        size.toString(8).padStart(11, '0').forEachIndexed { i, c -> header[124 + i] = c.code.toByte() }
        header[156] = (if (content == null) '5' else '0').code.toByte()
        // checksum（解析器不校验，填空格即可）
        var sum = 0
        header.forEachIndexed { i, b ->
            if (i !in 148..155) sum += b.toInt() and 0xff else sum += 0x20
        }
        sum.toString(8).padStart(6, '0').forEachIndexed { i, c -> header[148 + i] = c.code.toByte() }
        header[154] = 0.toByte()
        header[155] = 0x20.toByte()
        val out = ByteArrayOutputStream()
        out.write(header)
        if (content != null) {
            out.write(content)
            val pad = (512 - content.size % 512) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
        return out.toByteArray()
    }
}
