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
    fun `convertRepo rejects ui only plugins`() {
        val dir = tempRepo()
        try {
            File(dir, "package.json").writeText("""{"name":"ui-panel","description":"纯 UI 增强"}""")
            File(dir, "lib/client.js").apply { parentFile.mkdirs() }.writeText("export function apply(ctx) {}")

            val error = runCatching { adapter.convertRepo(dir, DshRepoRef("owner", "ui-panel", "main")) }
                .exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains("无可迁移能力"))
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
