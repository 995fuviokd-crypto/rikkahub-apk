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

    @Test
    fun `item author accepts object and string forms`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val asObject = json.decodeFromString<OperitListItem>(
            """{"id":"x","title":"t","source":{"kind":"script","url":"https://example.com/a"},
                "author":{"id":"gh_88519250","login":"youssef"},"publisher":"pub"}"""
        )
        assertEquals("youssef", asObject.displayAuthor)

        val asString = json.decodeFromString<OperitListItem>(
            """{"id":"x","title":"t","source":{"kind":"script","url":"https://example.com/a"},"author":"legacy-author","publisher":"pub"}"""
        )
        assertEquals("legacy-author", asString.displayAuthor)
    }

    @Test
    fun `item publisher accepts object without crashing`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        // 真实 Operit 响应：author / publisher 均为对象 {id, login, avatar}
        val real = json.decodeFromString<OperitListItem>(
            """{"id":"x","title":"t","source":{"kind":"script","url":"https://example.com/a"},
                "author":{"id":"gh_88519250","login":"yanjun62","avatar":"https://avatars.example/u/88519250?v=4"},
                "publisher":{"id":"gh_88519250","login":"yanjun62","avatar":"https://avatars.example/u/88519250?v=4"}}"""
        )
        assertEquals("yanjun62", real.displayAuthor)

        // author 为空对象、publisher 提供名字时回退到 publisher
        val viaPublisher = json.decodeFromString<OperitListItem>(
            """{"id":"x","title":"t","source":{"kind":"script","url":"https://example.com/a"},
                "author":{},"publisher":{"id":"gh_1","login":"publisherLogin"}}"""
        )
        assertEquals("publisherLogin", viaPublisher.displayAuthor)
    }

    // ---- 全类型适配：来源解析与资产处理 ----

    @Test
    fun `resolveOperitHandle prefers github_repo source over assets`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val skill = json.decodeFromString<OperitListItem>(
            """{"id":"skill-https-github-com-luck-104n-operit-pack-tree-main-skills-x",
                "title":"t","source":{"kind":"github_repo","url":"https://github.com/Luck-104n/operit-pack/tree/main/skills/x"},
                "latestVersion":{"source":null}}"""
        )
        val handle = resolveOperitHandle(skill)
        assertTrue(handle is OperitSourceHandle.GitHubDir)
        val dir = handle as OperitSourceHandle.GitHubDir
        assertEquals("Luck-104n", dir.source.owner)
        assertEquals("operit-pack", dir.source.repo)
        assertEquals("skills/x", dir.source.path)
    }

    @Test
    fun `resolveOperitHandle falls back to release asset for package and script`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val pkg = json.decodeFromString<OperitListItem>(
            """{"id":"package-com-operit-gentle-guardian-0-6-0","title":"t","source":null,
                "assets":[{"id":"a1","kind":"github_release_asset",
                    "url":"https://github.com/y/OperitForge/releases/download/x/y.toolpkg","assetName":"y.toolpkg"}]}"""
        )
        val handle = resolveOperitHandle(pkg)
        assertTrue(handle is OperitSourceHandle.ReleaseAsset)
        val asset = handle as OperitSourceHandle.ReleaseAsset
        assertEquals("y.toolpkg", asset.assetName)
        assertTrue(asset.url.contains("y.toolpkg"))
    }

    @Test
    fun `resolveOperitHandle derives github source from id when source missing`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val item = json.decodeFromString<OperitListItem>(
            """{"id":"skill-https-github-com-leilaomi-operit-coding-skills","title":"t","source":null,"assets":[]}"""
        )
        val handle = resolveOperitHandle(item)
        assertTrue(handle is OperitSourceHandle.GitHubDir)
        val dir = handle as OperitSourceHandle.GitHubDir
        assertEquals("leilaomi", dir.source.owner)
        assertEquals("operit-coding-skills", dir.source.repo)
    }

    @Test
    fun `resolveOperitHandle fails when no source and no assets`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val item = json.decodeFromString<OperitListItem>("""{"id":"script-x","title":"t","source":null,"assets":[]}""")
        try {
            resolveOperitHandle(item)
            assertTrue("应当抛出异常", false)
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("内容来源"))
        }
    }

    @Test
    fun `operit assets field deserializes release urls`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val item = json.decodeFromString<OperitListItem>(
            """{"id":"script-chat-permission-filter","title":"t","source":null,
                "assets":[{"id":"a","versionId":"v","kind":"github_release_asset",
                    "url":"https://github.com/w/OperitForge/releases/download/x/chat.js","sha256":"abc","assetName":"chat.js"}]}"""
        )
        assertEquals(1, item.assets.size)
        assertEquals("github_release_asset", item.assets[0].kind)
        assertEquals("chat.js", item.assets[0].assetName)
    }

    @Test
    fun `detectOperitAssetFormat identifies zip gzip and text`() {
        assertEquals(OperitAssetFormat.ZIP, detectOperitAssetFormat(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)))
        assertEquals(OperitAssetFormat.GZIP, detectOperitAssetFormat(byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 8, 0)))
        assertEquals(OperitAssetFormat.TEXT, detectOperitAssetFormat("/* METADATA */".toByteArray()))
    }

    @Test
    fun `parseOperitScriptMetadata extracts name and localized display`() {
        val script = """
            /*
            METADATA
            {
              "name": "chat_permission_filter",
              "display_name": {"zh": "增强对话", "en": "Enhanced Chat"},
              "description": {"zh": "按角色卡隔离", "en": "Isolated"}
            }
            */
            function run() {}
        """.trimIndent().toByteArray()
        val meta = parseOperitScriptMetadata(script)
        assertEquals("chat_permission_filter", meta["name"])
        assertTrue(meta["display_name"].orEmpty().contains("增强对话"))
        assertTrue(meta["description"].orEmpty().contains("按角色卡隔离"))
    }

    @Test
    fun `parseOperitScriptMetadata tolerates non-json body`() {
        assertEquals(emptyMap<String, String>(), parseOperitScriptMetadata("function run() {}".toByteArray()))
    }

    @Test
    fun `operitPluginIdFor produces safe stable id`() {
        val id = operitPluginIdFor("package-com-operit-gentle-guardian-artifact-0-6-0")
        assertTrue(id.startsWith("operit-"))
        assertEquals(id, operitPluginIdFor("package-com-operit-gentle-guardian-artifact-0-6-0"))
        assertTrue(id.all { it.isLetterOrDigit() || it == '-' })
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
