package me.rerere.rikkahub.data.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** OpenAI 插件清单（/.well-known/ai-plugin.json） */
@Serializable
data class OpenAIPluginManifest(
    val schema_version: String = "",
    val name_for_human: String = "",
    val name_for_model: String = "",
    val description_for_human: String = "",
    val description_for_model: String = "",
    val auth: OpenAIPluginAuth = OpenAIPluginAuth(),
    val api: OpenAIPluginApi = OpenAIPluginApi(),
    val logo_url: String = "",
    val contact_email: String = "",
    val legal_info_url: String = "",
)

@Serializable
data class OpenAIPluginAuth(
    val type: String = "",
)

@Serializable
data class OpenAIPluginApi(
    val type: String = "",
    val url: String = "",
    val has_user_authentication: Boolean = false,
)

/**
 * OpenAI 系 plugin 仓库适配器：读取 /.well-known/ai-plugin.json，
 * 自动转换为 RikkaHub 插件 zip（plugin.json + 附带原始 manifest），无需用户配置开关。
 */
class OpenAIPluginAdapter(
    private val httpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    /** 根据 baseUrl 拉取 ai-plugin.json 并转换为插件 zip 字节 */
    suspend fun fetchAsZip(baseUrl: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val input = baseUrl.trim().trimEnd('/')
            if (input.isBlank()) error("请填写插件仓库地址")
            val candidates = buildCandidateUrls(input)
            var lastError: Throwable? = null
            for (manifestUrl in candidates) {
                try {
                    val manifest = fetchManifest(manifestUrl)
                    return@withContext Result.success(buildZip(manifest, manifestUrl))
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            error(lastError?.message ?: "获取 ai-plugin.json 失败")
        }
    }

    internal fun buildCandidateUrls(input: String): List<String> {
        val urls = mutableListOf<String>()
        val isGithubUrl = input.startsWith("github.com") || input.contains("github.com/")
        // GitHub 仓库：完整链接（github.com/owner/repo），或裸 owner/repo（无协议、非域名）
        val isBareRepo = !isGithubUrl && !input.contains("://") && !input.contains(".")
        if (isGithubUrl || isBareRepo) {
            val parts = input.substringAfter("github.com/").trim('/').split('/')
            if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                val owner = parts[0]
                val repo = parts[1]
                urls += "https://raw.githubusercontent.com/$owner/$repo/main/.well-known/ai-plugin.json"
                urls += "https://raw.githubusercontent.com/$owner/$repo/master/.well-known/ai-plugin.json"
            }
        }
        val host = input
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
        if (host.isNotBlank() && host.contains(".")) {
            val base = "https://$host"
            urls += "$base/.well-known/ai-plugin.json"
        }
        return urls.distinct()
    }

    private fun fetchManifest(manifestUrl: String): OpenAIPluginManifest {
        val client = httpClient.newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val response = client.newCall(
            okhttp3.Request.Builder().url(manifestUrl).build()
        ).execute()
        response.use {
            if (!it.isSuccessful) error("获取 ai-plugin.json 失败: HTTP ${it.code}")
            val text = it.body?.string() ?: error("空响应")
            if (text.isBlank()) error("ai-plugin.json 为空")
            return json.decodeFromString(OpenAIPluginManifest.serializer(), text)
        }
    }

    private fun buildZip(manifest: OpenAIPluginManifest, manifestUrl: String): ByteArray {
        val info = manifestToInfo(manifest, manifestUrl)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry(PluginManager.METADATA_FILE))
            zip.write(PluginJson.toJson(info).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("openai-ai-plugin.json"))
            zip.write(json.encodeToString(OpenAIPluginManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("README.md"))
            zip.write(
                """
                # ${info.name}

                由 OpenAI 兼容插件仓库自动转换导入（来源：$manifestUrl）。

                - description_for_model 已注入为系统提示
                - API 端点：${manifest.api.url}
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    /** 纯逻辑：OpenAI 清单转 PluginInfo（供单元测试与复用） */
    internal fun manifestToInfo(
        manifest: OpenAIPluginManifest,
        sourceUrl: String = "",
    ): PluginInfo {
        val base = manifest.name_for_model.trim().lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(40)
        val id = base.ifEmpty { "openai-${Math.abs(manifest.name_for_model.hashCode())}" }
        return PluginInfo(
            id = id,
            name = manifest.name_for_human.ifBlank { manifest.name_for_model },
            version = "1.0.0",
            description = manifest.description_for_human,
            author = manifest.contact_email,
            category = "general",
            repository = sourceUrl,
            systemPrompt = manifest.description_for_model,
            type = "plugin",
            tags = listOf("openai", "ai-plugin"),
        )
    }
}
