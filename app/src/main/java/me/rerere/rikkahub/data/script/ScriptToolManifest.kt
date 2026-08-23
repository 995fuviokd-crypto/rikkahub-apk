package me.rerere.rikkahub.data.script

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
import me.rerere.rikkahub.data.api.parseScriptMetaObject
import java.io.File

/** 单个 脚本工具的能力定义 */
@Serializable
data class ScriptToolDef(
    val name: String,
    val description: String = "",
)

/** 插件内的 脚本工具清单（toolmanifest.json），供运行期列出可用工具 */
@Serializable
data class ScriptToolManifestData(
    val name: String,
    val description: String = "",
    val tools: List<ScriptToolDef> = emptyList(),
)

/** 生成与解析 脚本工具清单。纯 JVM 可测，不依赖 Android。 */
object ScriptToolManifest {
    private val json = Json { ignoreUnknownKeys = true }

    /** 从 script / toolpkg 子包的 METADATA.tools 提取工具定义 */
    fun toolsFromMetadata(bytes: ByteArray): List<ScriptToolDef> {
        val meta = parseScriptMetaObject(bytes) ?: return emptyList()
        return toolsFromMetaObject(meta)
    }

    fun toolsFromMetaObject(meta: JsonObject): List<ScriptToolDef> {
        val tools = meta["tools"] as? JsonArray ?: return emptyList()
        return tools.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = jsonLocalizedString(obj, "name", "display_name") ?: return@mapNotNull null
            val description = jsonLocalizedString(obj, "description", "summary")
                ?: obj["parameters"]?.toString()?.take(200).orEmpty()
            ScriptToolDef(name = name, description = description)
        }.distinctBy { it.name }
    }

    /** 遍历目录下所有 .js 文件，聚合各文件 METADATA.tools */
    fun toolsFromDirectory(dir: File): List<ScriptToolDef> {
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

    fun buildJson(data: ScriptToolManifestData): String = json.encodeToString(data)

    fun parseJson(text: String): ScriptToolManifestData? {
        return runCatching { json.decodeFromString<ScriptToolManifestData>(text) }.getOrNull()
    }

    /** 生成描述脚本工具能力的 systemPrompt 片段 */
    fun describeSystemPrompt(name: String, description: String, tools: List<ScriptToolDef>): String {
        val sb = StringBuilder()
        sb.append("这是来自 社区市场的资源「").append(name).append("」。")
        if (description.isNotBlank()) sb.append("简介：").append(description).append("\n")
        if (tools.isNotEmpty()) {
            sb.append("该资源已通过 RikkaHub 本地引擎真实加载，提供以下工具，可用 `run_script_tool` 工具按需调用（需在助手工具设置中开启「脚本」）：\n")
            tools.forEach { tool ->
                sb.append("- ").append(tool.name)
                if (tool.description.isNotBlank()) sb.append("：").append(tool.description)
                sb.append("\n")
            }
            sb.append("脚本依赖的 Tools.* 运行时已本地映射：文件读写（Tools.Files）、HTTP 请求与网页抓取（Tools.Net）、")
            sb.append("系统能力如 sleep/toast/通知/设备信息（Tools.System）、表达式计算（Tools.calc）真实可用；")
            sb.append("UI 自动化、浏览器控制、Java 桥接等依赖 Shizuku/无障碍特权的 社区专有 API 返回受限提示。")
        } else {
            sb.append("脚本内容已保存在插件 script/ 目录，可用 `run_script_tool` 调用其导出函数（具体工具见插件说明）。")
        }
        return sb.toString()
    }
}
