package me.rerere.rikkahub.data.operit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.api.jsonLocalizedString
import me.rerere.rikkahub.data.api.parseOperitScriptMetaObject
import java.io.File

/** 单个 Operit 脚本工具的能力定义 */
@Serializable
data class OperitToolDef(
    val name: String,
    val description: String = "",
)

/** 插件内的 Operit 脚本工具清单（toolmanifest.json），供运行期列出可用工具 */
@Serializable
data class OperitToolManifestData(
    val name: String,
    val description: String = "",
    val tools: List<OperitToolDef> = emptyList(),
)

/** 生成与解析 Operit 工具清单。纯 JVM 可测，不依赖 Android。 */
object OperitToolManifest {
    private val json = Json { ignoreUnknownKeys = true }

    /** 从 script / toolpkg 子包的 METADATA.tools 提取工具定义 */
    fun toolsFromMetadata(bytes: ByteArray): List<OperitToolDef> {
        val meta = parseOperitScriptMetaObject(bytes) ?: return emptyList()
        return toolsFromMetaObject(meta)
    }

    fun toolsFromMetaObject(meta: JsonObject): List<OperitToolDef> {
        val tools = meta["tools"] as? JsonArray ?: return emptyList()
        return tools.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = jsonLocalizedString(obj, "name", "display_name") ?: return@mapNotNull null
            val description = jsonLocalizedString(obj, "description", "summary")
                ?: obj["parameters"]?.toString()?.take(200).orEmpty()
            OperitToolDef(name = name, description = description)
        }.distinctBy { it.name }
    }

    /** 遍历目录下所有 .js 文件，聚合各文件 METADATA.tools */
    fun toolsFromDirectory(dir: File): List<OperitToolDef> {
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "js" }
            .sortedBy { it.relativeTo(dir).path }
            .flatMap { file ->
                runCatching { toolsFromMetadata(file.readBytes()) }.getOrDefault(emptyList())
            }
            .distinctBy { it.name }
            .toList()
    }

    fun buildJson(data: OperitToolManifestData): String = json.encodeToString(data)

    fun parseJson(text: String): OperitToolManifestData? {
        return runCatching { json.decodeFromString<OperitToolManifestData>(text) }.getOrNull()
    }

    /** 生成描述脚本工具能力的 systemPrompt 片段 */
    fun describeSystemPrompt(name: String, description: String, tools: List<OperitToolDef>): String {
        val sb = StringBuilder()
        sb.append("这是来自 Operit 市场的资源「").append(name).append("」。")
        if (description.isNotBlank()) sb.append("简介：").append(description).append("\n")
        if (tools.isNotEmpty()) {
            sb.append("该资源已通过 RikkaHub 本地引擎真实加载，提供以下工具，可用 `run_operit_tool` 工具按需调用（需在助手工具设置中开启「Operit 脚本」）：\n")
            tools.forEach { tool ->
                sb.append("- ").append(tool.name)
                if (tool.description.isNotBlank()) sb.append("：").append(tool.description)
                sb.append("\n")
            }
            sb.append("脚本依赖的 Tools.* 运行时已本地映射：文件读写（Tools.Files）与系统通知（Tools.System）直接可用；")
            sb.append("对话/工作流等 Operit 专有 API 暂不可用，依赖它们的工具会返回受限提示。")
        } else {
            sb.append("脚本内容已保存在插件 operit/ 目录，可用 `run_operit_tool` 调用其导出函数（具体工具见插件说明）。")
        }
        return sb.toString()
    }
}
