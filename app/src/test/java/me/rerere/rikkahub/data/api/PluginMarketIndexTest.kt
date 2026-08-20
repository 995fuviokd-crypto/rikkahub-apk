package me.rerere.rikkahub.data.api

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginMarketIndexTest {

    private class FakeGitHubAPI(private val indexJson: String) : GitHubPluginAPI {
        override suspend fun getPluginIndex(owner: String, repo: String, accept: String): ResponseBody =
            indexJson.toResponseBody("application/json; charset=utf-8".toMediaType())

        override suspend fun getSubmissions(owner: String, repo: String, accept: String): ResponseBody =
            "[]".toResponseBody("application/json; charset=utf-8".toMediaType())

        override suspend fun getUser(authorization: String): GitHubUser = GitHubUser(login = "test")

        override suspend fun getContent(
            owner: String, repo: String, path: String, authorization: String?,
        ): GitHubContent = GitHubContent()

        override suspend fun uploadContent(
            owner: String, repo: String, path: String, authorization: String, body: GitHubCommitBody,
        ): GitHubCommitResponse = GitHubCommitResponse()
    }

    /** 官方市场 plugins.json 的真实结构（裸 JSON 数组） */
    private val realIndexJson = """
[
  {
    "id": "code-reviewer",
    "name": "代码审查助手",
    "version": "1.0.0",
    "description": "代码审查与讲解：发现缺陷、安全隐患与性能问题，并给出修复建议",
    "author": "RikkaHub Team",
    "category": "development",
    "repository": "https://github.com/995fuviokd-crypto/plugin-market",
    "downloadUrl": "https://github.com/995fuviokd-crypto/plugin-market/raw/main/plugins/code-reviewer-1.0.0.zip",
    "type": "plugin",
    "tags": [
      "code",
      "review",
      "development"
    ]
  },
  {
    "id": "document-master",
    "name": "文档解析助手",
    "version": "1.0.0",
    "description": "PDF/Word/PPT/EPUB 文档解析专家：总结、要点提取、翻译与问答",
    "author": "RikkaHub Team",
    "category": "productivity",
    "repository": "https://github.com/995fuviokd-crypto/plugin-market",
    "downloadUrl": "https://github.com/995fuviokd-crypto/plugin-market/raw/main/plugins/document-master-1.0.0.zip",
    "type": "plugin",
    "tags": [
      "document",
      "pdf",
      "office",
      "productivity"
    ]
  },
  {
    "id": "mcp-fetch-sample",
    "name": "MCP 服务器配置示例",
    "version": "1.0.0",
    "description": "MCP 配置示例包：Fetch 网页抓取与 Filesystem 文件系统服务器配置模板，可参考接入自己的 MCP 服务",
    "author": "RikkaHub Team",
    "category": "development",
    "repository": "https://github.com/995fuviokd-crypto/plugin-market",
    "downloadUrl": "https://github.com/995fuviokd-crypto/plugin-market/raw/main/plugins/mcp-fetch-sample-1.0.0.zip",
    "type": "mcp",
    "tags": [
      "mcp",
      "server",
      "config",
      "development"
    ]
  },
  {
    "id": "prompt-optimizer",
    "name": "提示词优化助手",
    "version": "1.0.0",
    "description": "提示词工程助手：优化、重构、结构化提示词，提供优化前后对比与理由",
    "author": "RikkaHub Team",
    "category": "creative",
    "repository": "https://github.com/995fuviokd-crypto/plugin-market",
    "downloadUrl": "https://github.com/995fuviokd-crypto/plugin-market/raw/main/plugins/prompt-optimizer-1.0.0.zip",
    "type": "plugin",
    "tags": [
      "prompt",
      "creative",
      "writing"
    ]
  },
  {
    "id": "translation-pro",
    "name": "翻译润色专家",
    "version": "1.0.0",
    "description": "高质量中英互译与文本润色：术语统一、格式保留、风格适配",
    "author": "RikkaHub Team",
    "category": "productivity",
    "repository": "https://github.com/995fuviokd-crypto/plugin-market",
    "downloadUrl": "https://github.com/995fuviokd-crypto/plugin-market/raw/main/plugins/translation-pro-1.0.0.zip",
    "type": "plugin",
    "tags": [
      "translation",
      "writing",
      "productivity"
    ]
  },
  {
    "id": "web-search-pro",
    "name": "联网搜索增强",
    "version": "1.0.0",
    "description": "增强联网搜索能力：多来源交叉查证、优先权威信息、结论附带来源与时效性标注",
    "author": "RikkaHub Team",
    "category": "development",
    "repository": "https://github.com/995fuviokd-crypto/plugin-market",
    "downloadUrl": "https://github.com/995fuviokd-crypto/plugin-market/raw/main/plugins/web-search-pro-1.0.0.zip",
    "type": "plugin",
    "tags": [
      "search",
      "web",
      "research",
      "development"
    ]
  },
  {
    "id": "workflow-planner",
    "name": "任务规划技能",
    "version": "1.0.0",
    "description": "技能包：将复杂目标拆解为有序、可验证的步骤并持续跟踪执行",
    "author": "RikkaHub Team",
    "category": "automation",
    "repository": "https://github.com/995fuviokd-crypto/plugin-market",
    "downloadUrl": "https://github.com/995fuviokd-crypto/plugin-market/raw/main/plugins/workflow-planner-1.0.0.zip",
    "type": "skill",
    "tags": [
      "workflow",
      "planning",
      "automation"
    ]
  }
]
""".trimIndent()

    @Test
    fun `解析市场索引裸 JSON 数组并识别全部插件`() {
        val ds = PluginMarketDataSource(FakeGitHubAPI(realIndexJson), OkHttpClient())
        val result = runBlocking { ds.parseIndex("995fuviokd-crypto/plugin-market") }
        assertTrue("解析失败: ${result.exceptionOrNull()}", result.isSuccess)
        val entries = result.getOrThrow()
        assertEquals(7, entries.size)
        assertTrue(entries.any { it.id == "web-search-pro" })
        assertEquals("mcp", entries.first { it.id == "mcp-fetch-sample" }.type)
        assertTrue(entries.all { it.downloadUrl.startsWith("https://github.com/") })
    }
}
