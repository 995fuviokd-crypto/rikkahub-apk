package me.rerere.rikkahub.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DshPluginAdapterTest {

    private val adapter = DshPluginAdapter(okhttp3.OkHttpClient())

    private fun tempRepo(): File = kotlin.io.path.createTempDirectory("dsh-repo").toFile()

    // ---- parseRepoRef ----

    @Test
    fun `parseRepoRef accepts dsh style github ref`() {
        val ref = adapter.parseRepoRef("github:liustack/modlens#dev")
        assertNotNull(ref)
        assertEquals("liustack", ref!!.owner)
        assertEquals("modlens", ref.repo)
        assertEquals("dev", ref.ref)
        assertEquals("liustack-modlens", ref.slug)
    }

    @Test
    fun `parseRepoRef accepts https url with tree ref`() {
        val ref = adapter.parseRepoRef("https://github.com/liustack/modlens/tree/v2")
        assertEquals("v2", ref?.ref)
        assertEquals("modlens", ref?.repo)
    }

    @Test
    fun `parseRepoRef accepts bare owner slash repo and git suffix`() {
        val ref = adapter.parseRepoRef("zhu1090093659/dsh-web-ui.git")
        assertEquals("zhu1090093659", ref?.owner)
        assertEquals("dsh-web-ui", ref?.repo)
        assertEquals("main", ref?.ref)
    }

    @Test
    fun `parseRepoRef rejects invalid input`() {
        assertEquals(null, adapter.parseRepoRef(""))
        assertEquals(null, adapter.parseRepoRef("just-a-name"))
        assertEquals(null, adapter.parseRepoRef("https://gitlab.com/a/b"))
    }

    // ---- convertRepo：skills 资源分支 ----

    @Test
    fun `convertRepo maps SKILL md to skill plugin`() {
        val dir = tempRepo()
        try {
            File(dir, "package.json").writeText(
                """{"name":"my-skill","description":"测试技能","version":"1.2.0"}"""
            )
            File(dir, "skills/demo/SKILL.md").apply { parentFile.mkdirs() }
                .writeText("# 技能正文\nDSH 技能内容。")

            val info = adapter.convertRepo(dir, DshRepoRef("owner", "my-skill", "main"))

            assertEquals("skill", info.type)
            assertEquals("dsh-owner-my-skill", info.id)
            assertEquals("my-skill", info.name)
            assertEquals("1.2.0", info.version)
            assertTrue(info.systemPrompt.contains("技能正文"))
            assertEquals("owner", info.author)
            assertEquals(listOf("dsh", "skill"), info.tags)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- convertRepo：defineTool 分支 ----

    @Test
    fun `convertRepo extracts defineTool declarations as prompt plugin`() {
        val dir = tempRepo()
        try {
            File(dir, "package.json").writeText(
                """{"name":"toolkit","description":"工具集"}"""
            )
            File(dir, "lib/index.js").apply { parentFile.mkdirs() }.writeText(
                """
                export function apply(ctx) {
                  ctx.tools.register(
                    defineTool({
                      name: 'read_file',
                      description: '读取文件内容并返回文本',
                      parameters: { path: 'string' },
                      async execute(args) { return ctx.fs.read(args.path); },
                    }),
                  );
                  ctx.tools.register(
                    defineTool({
                      name: "list_dir",
                      description: "列出目录下的文件",
                      parameters: {},
                      async execute() {},
                    }),
                  );
                }
                """.trimIndent()
            )

            val info = adapter.convertRepo(dir, DshRepoRef("owner", "toolkit", "main"))

            assertEquals("plugin", info.type)
            assertTrue(info.systemPrompt.contains("read_file"))
            assertTrue(info.systemPrompt.contains("读取文件内容并返回文本"))
            assertTrue(info.systemPrompt.contains("list_dir"))
            assertTrue(info.systemPrompt.contains("DeepSeek Harness"))
            assertEquals(listOf("dsh"), info.tags)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `convertRepo falls back to rich README docs`() {
        val dir = tempRepo()
        try {
            File(dir, "package.json").writeText("""{"name":"docs-only","description":"纯文档插件"}""")
            File(dir, "README.md").writeText("# 文档型插件\n\n".repeat(30) + "使用说明细节，超过最小说明长度要求。")

            val info = adapter.convertRepo(dir, DshRepoRef("owner", "docs-only", "main"))

            assertEquals("skill", info.type)
            assertTrue(info.systemPrompt.contains("文档型插件"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `convertRepo converts ui only plugins to docs carrier with sidebar entry`() {
        val dir = tempRepo()
        try {
            File(dir, "package.json").writeText("""{"name":"ui-panel","description":"纯 UI 增强"}""")
            File(dir, "lib/client.js").apply { parentFile.mkdirs() }.writeText("export function apply(ctx) {}")

            val info = adapter.convertRepo(dir, DshRepoRef("owner", "ui-panel", "main"))

            // UI 面板类不再拒装：以文档承载型 skill 落地，侧边栏注册面板入口
            assertEquals("skill", info.type)
            assertEquals("ui", info.category)
            assertTrue(info.tags.contains("ui"))
            val sidebar = info.extensionPoints.sidebarActions
            assertEquals(1, sidebar.size)
            assertEquals("webview", sidebar[0].target)
            assertEquals("plugin://dsh-owner-ui-panel/index.html", sidebar[0].payload)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `convertRepo falls back to dsh plugin json metadata`() {
        val dir = tempRepo()
        try {
            File(dir, "dsh.plugin.json").writeText(
                """{"name":"清单名","version":"2.5.0","description":"清单简介","author":"清单作者"}"""
            )

            val info = adapter.convertRepo(dir, DshRepoRef("owner", "meta-repo", "main"))

            assertEquals("清单名", info.name)
            assertEquals("2.5.0", info.version)
            assertEquals("清单简介", info.description)
            assertEquals("清单作者", info.author)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `convertRepo prefers package json over dsh manifest metadata`() {
        val dir = tempRepo()
        try {
            File(dir, "package.json").writeText("""{"name":"pkg-name","version":"1.0.1"}""")
            File(dir, "dsh.plugin.json").writeText("""{"name":"清单名","version":"2.5.0"}""")

            val info = adapter.convertRepo(dir, DshRepoRef("owner", "prio-repo", "main"))

            assertEquals("pkg-name", info.name)
            assertEquals("1.0.1", info.version)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- markdownToHtml / 文档页 ----

    @Test
    fun `markdownToHtml renders headings lists code and resolves relative images`() {
        val md = """
            # 标题一
            一些 **粗体** 与 `code` 文本。
            - 列表项 [链接](docs/guide.md)
            ![截图](images/demo.png)

            ```js
            console.log("<script>");
            ```
        """.trimIndent()
        val ref = DshRepoRef("owner", "repo", "main")
        val html = adapter.markdownToHtml(md, ref)

        assertTrue(html.contains("<h1>标题一</h1>"))
        assertTrue(html.contains("<b>粗体</b>"))
        assertTrue(html.contains("<code>code</code>"))
        assertTrue(html.contains("<li>列表项 <a href=\"https://github.com/owner/repo/blob/main/docs/guide.md\">链接</a></li>"))
        assertTrue(html.contains("__RAW_BASE__images/demo.png"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `buildDocsPage embeds readme and meta link`() {
        val dir = tempRepo()
        try {
            File(dir, "README.md").writeText("# My Panel\n\n功能说明正文。")
            val page = adapter.buildDocsPage(dir, DshRepoRef("my", "panel", "v2"))

            assertTrue(page.startsWith("<!DOCTYPE html>"))
            assertTrue(page.contains("<h1>My Panel</h1>"))
            assertTrue(page.contains("https://github.com/my/panel"))
            assertTrue(page.replace("__RAW_BASE__", "x").contains("<body>"))
            // raw base 已注入供相对图片解析
            assertTrue(page.contains("__RAW_BASE__") || !page.contains("images/"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `buildDocsPage handles missing readme`() {
        val dir = tempRepo()
        try {
            val page = adapter.buildDocsPage(dir, DshRepoRef("o", "r", "main"))
            assertTrue(page.contains("未提供 README"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- findClientEntry / 面板运行壳 ----

    @Test
    fun `findClientEntry prefers dsh plugin json declaration`() {
        val dir = tempRepo()
        try {
            File(dir, "dsh.plugin.json").writeText(
                """{"id":"x","client":{"main":"./ui/panel.js"}}"""
            )
            File(dir, "lib/client.js").apply { parentFile.mkdirs() }.writeText("// fallback")
            File(dir, "ui/panel.js").apply { parentFile.mkdirs() }.writeText("// declared")

            assertEquals(File(dir, "ui/panel.js"), adapter.findClientEntry(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `findClientEntry falls back to common bundle paths`() {
        val dir = tempRepo()
        try {
            File(dir, "lib/client.js").apply { parentFile.mkdirs() }.writeText("// client")
            assertEquals(File(dir, "lib/client.js"), adapter.findClientEntry(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `findClientEntry returns null without client code`() {
        val dir = tempRepo()
        try {
            assertEquals(null, adapter.findClientEntry(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `buildPanelPage embeds host shim and client reference`() {
        val docs = "<!DOCTYPE html><html><head></head><body><p>doc body</p></body></html>"
        val page = adapter.buildPanelPage(
            DshRepoRef("owner", "repo", "main"),
            clientJs = "window.__ModuleLoader__.load({});",
            docsPageHtml = docs,
        )

        assertTrue(page.contains("__ModuleLoader__"))
        assertTrue(page.contains("""<script src="./plugin.client.js">"""))
        assertTrue(page.contains("docs-fallback"))
        assertTrue(page.contains("<p>doc body</p>"))
        // 面板壳与状态条
        assertTrue(page.contains("id=\"panel-root\""))
        assertTrue(page.contains("__dshPanelMountAll__"))
    }

    @Test
    fun `convertToZip packages metadata web page and client bundle`() {
        val dir = tempRepo()
        try {
            File(dir, "SKILL.md").writeText("# Sample\n\nFollow these steps.")
            File(dir, "lib/client.js").apply { parentFile.mkdirs() }.writeText("// client bundle")

            val ref = DshRepoRef("owner", "repo", "main")
            val info = adapter.convertRepo(dir, ref)
            val clientEntry = adapter.findClientEntry(dir)
            val docsPage = adapter.buildDocsPage(dir, ref)
            val indexHtml = if (clientEntry != null) {
                adapter.buildPanelPage(ref, clientEntry.readText(), docsPage)
            } else {
                docsPage
            }
            val zipBytes = adapter.convertToZip(info, indexHtml, clientJs = clientEntry?.readText())

            val names = mutableListOf<String>()
            val contents = mutableMapOf<String, String>()
            java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zis ->
                generateSequence { zis.nextEntry }.forEach { entry ->
                    names += entry.name
                    contents[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                    zis.closeEntry()
                }
            }

            assertTrue(PluginManager.METADATA_FILE in names)
            assertTrue("web/index.html" in names)
            assertTrue("web/plugin.client.js" in names)

            val meta = contents.getValue(PluginManager.METADATA_FILE)
            assertTrue(meta.contains("\"id\""))
            assertTrue(contents.getValue("web/index.html").contains("""<script src="./plugin.client.js">"""))
            assertEquals("// client bundle", contents.getValue("web/plugin.client.js"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- buildWorkspaceCommandHint：npm bin → 工作区命令 ----

    @Test
    fun `buildWorkspaceCommandHint generates npx commands for npm bin`() {
        val dir = tempRepo()
        try {
            File(dir, "package.json").writeText(
                """{"name":"@liustack/modlens","bin":{"modlens":"./dist/main.js"}}"""
            )

            val hint = adapter.buildWorkspaceCommandHint("@liustack/modlens", dir)

            assertTrue(hint.contains("工作区命令能力"))
            assertTrue(hint.contains("`modlens`"))
            assertTrue(hint.contains("npx -y @liustack/modlens"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `buildWorkspaceCommandHint handles string bin form`() {
        val dir = tempRepo()
        try {
            File(dir, "package.json").writeText(
                """{"name":"simple-cli","bin":"./cli.js"}"""
            )

            val hint = adapter.buildWorkspaceCommandHint("simple-cli", dir)

            assertTrue(hint.contains("`simple-cli`"))
            assertTrue(hint.contains("npx -y simple-cli"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `buildWorkspaceCommandHint returns empty without bin or package`() {
        val dir = tempRepo()
        try {
            File(dir, "package.json").writeText("""{"name":"no-bin"}""")
            assertEquals("", adapter.buildWorkspaceCommandHint("no-bin", dir))
            assertEquals("", adapter.buildWorkspaceCommandHint(null, dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- convertRepo：npm CLI 分支（无 skills / defineTool 但有 bin） ----

    @Test
    fun `convertRepo maps npm cli to workspace command plugin`() {
        val dir = tempRepo()
        try {
            File(dir, "package.json").writeText(
                """{"name":"vision-cli","version":"2.0.0","description":"视觉命令行","bin":{"vision-cli":"./cli.js"}}"""
            )
            File(dir, "README.md").writeText("短 readme")

            val info = adapter.convertRepo(dir, DshRepoRef("owner", "vision-cli", "main"))

            assertEquals("tools", info.category)
            assertTrue(info.tags.contains("cli"))
            assertTrue(info.systemPrompt.contains("npx -y vision-cli"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
