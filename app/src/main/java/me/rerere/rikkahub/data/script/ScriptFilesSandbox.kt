package me.rerere.rikkahub.data.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/**
 * Files 沙箱：把 Tools.Files 工具调用映射为 RikkaHub 插件数据目录内的文件操作。
 *
 * 本类刻意保持平台无关（File API + java.util.Base64），以便在 JVM 单测中直接验证各类
 * 参数形态与返回结构，不依赖 Android Context。调用统一返回 [JsonObject] 便于 WebView 桥透传。
 *
 * 参数兼容约定（脚本常见调用形态，宿主做适配层归一）：
 *  - 路径参数既接受字符串（`read("a/b")`），也接受对象（`read({ path: "a/b", environment: "android" })`），
 *    对象形态时忽略 environment 字段，始终落在插件数据目录 sandbox 内。
 *  - 多余的位置参数（如 `mkdir(dir, true, "android")` 的递归标记与环境）被忽略。
 *  - 方法别名：makeDirectory = mkdir，deleteFile = delete，readText = read，writeText = write。
 *  - readBinary 同时返回 content 与 contentBase64 两个字段，兼容两种消费端。
 */
class ScriptFilesSandbox(private val root: File) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    fun handle(method: String, args: List<JsonElement>): JsonObject {
        fun str(idx: Int): String? = pathArg(args.getOrNull(idx))
        fun strOrEmpty(idx: Int): String = str(idx).orEmpty()
        return when (method) {
            "mkdir", "makeDirectory" -> handleMkdir(str(0))
            "exists" -> {
                val f = resolve(str(0))
                buildJsonObject { put("exists", f?.exists() == true) }
            }
            "read", "readText" -> {
                val f = resolve(str(0))
                if (f == null || !f.isFile) {
                    okOp("文件不存在", content = null, path = str(0).orEmpty())
                } else {
                    okOp("ok", content = runCatching { f.readText() }.getOrDefault(""), path = f.path)
                }
            }
            "readPart" -> {
                val f = resolve(str(0))
                val start = str(1)?.toIntOrNull() ?: 1
                val end = str(2)?.toIntOrNull()
                if (f == null || !f.isFile) {
                    okOp("文件不存在", content = null)
                } else {
                    val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
                    val slice = if (end == null) {
                        lines.drop((start - 1).coerceAtLeast(0))
                    } else {
                        val from = (start - 1).coerceIn(0, lines.size)
                        val to = end.coerceIn(from, lines.size)
                        lines.subList(from, to)
                    }
                    okOp("ok", content = slice.joinToString("\n"), path = f.path)
                }
            }
            "write", "writeText", "create" -> {
                val f = resolve(str(0)) ?: return okOp("路径无效")
                val contentEl = args.getOrNull(1)
                val content: String = when (contentEl) {
                    is JsonPrimitive -> contentEl.content
                    null, is JsonObject, is JsonArray -> json.encodeToString(contentEl ?: JsonPrimitive(""))
                    else -> ""
                }
                val append = (args.getOrNull(2) as? JsonPrimitive)?.content == "true"
                    || (args.getOrNull(2) as? JsonPrimitive)?.booleanOrNull == true
                f.parentFile?.mkdirs()
                val ok = runCatching { if (append) f.appendText(content) else f.writeText(content); true }.getOrDefault(false)
                okOp(if (ok) "已写入" else "写入失败", path = f.path)
            }
            "writeBinary" -> {
                val f = resolve(str(0)) ?: return okOp("路径无效")
                val base64 = str(1).orEmpty()
                val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
                    ?: return okOp("base64 解析失败")
                f.parentFile?.mkdirs()
                val ok = runCatching { f.writeBytes(bytes); true }.getOrDefault(false)
                okOp(if (ok) "已写入" else "写入失败", path = f.path)
            }
            "readBinary" -> {
                val f = resolve(str(0))
                if (f == null || !f.isFile) {
                    okOp("文件不存在", content = null)
                } else {
                    val b64 = runCatching { Base64.getEncoder().encodeToString(f.readBytes()) }.getOrDefault("")
                    buildJsonObject {
                        put("ok", true)
                        put("success", true)
                        put("message", "ok")
                        put("content", b64)
                        put("contentBase64", b64)
                        put("path", f.path)
                    }
                }
            }
            "delete", "deleteFile" -> {
                val f = resolve(str(0)) ?: return okOp("路径无效")
                val recursive = (args.getOrNull(1) as? JsonPrimitive)?.content == "true"
                    || (args.getOrNull(1) as? JsonPrimitive)?.booleanOrNull == true
                val ok = runCatching {
                    if (recursive && f.isDirectory) f.deleteRecursively() else f.delete()
                }.getOrDefault(false)
                okOp(if (ok) "已删除" else "删除失败", path = f.path)
            }
            "list" -> {
                val f = resolve(str(0))
                val path = f?.path ?: str(0).orEmpty()
                if (f == null || !f.isDirectory) {
                    buildJsonObject { put("ok", true); put("path", path); put("files", JsonArray(emptyList())) }
                } else {
                    val entries = f.listFiles()?.sortedBy { it.name } ?: emptyList()
                    buildJsonObject {
                        put("ok", true)
                        put("path", path)
                        put("files", JsonArray(entries.map { e ->
                            buildJsonObject {
                                put("name", e.name)
                                put("path", e.path)
                                put("type", if (e.isDirectory) "directory" else "file")
                            }
                        }))
                    }
                }
            }
            "move" -> {
                val src = resolve(str(0)); val dst = resolve(str(1))
                if (src == null || dst == null) okOp("路径无效")
                else {
                    dst.parentFile?.mkdirs()
                    val ok = runCatching { src.renameTo(dst) || (src.copyRecursively(dst) && src.deleteRecursively()) }.getOrDefault(false)
                    okOp(if (ok) "已移动" else "移动失败", path = dst.path)
                }
            }
            "copy" -> {
                val src = resolve(str(0)); val dst = resolve(str(1))
                if (src == null || dst == null) okOp("路径无效")
                else {
                    dst.parentFile?.mkdirs()
                    val ok = runCatching {
                        if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst, overwrite = true)
                    }.isSuccess
                    okOp(if (ok) "已复制" else "复制失败", path = dst.path)
                }
            }
            "find" -> {
                val f = resolve(str(0)) ?: return okOp("路径无效", files = emptyList())
                val pattern = str(1).orEmpty()
                val matcher = runCatching { Regex(pattern) }.getOrNull()
                val matches = if (f.isDirectory) {
                    f.walkTopDown().filter { it.isFile }
                        .filter { matcher?.matches(it.name) == true || (matcher == null && it.name.contains(pattern)) }
                        .take(200).map { it.path }.toList()
                } else emptyList()
                okOp("ok", files = matches)
            }
            "grep" -> {
                val f = resolve(str(0)) ?: return okOp("路径无效", files = emptyList())
                val pattern = str(1).orEmpty()
                val matcher = runCatching { Regex(pattern) }.getOrNull()
                    ?: return okOp("正则无效", files = emptyList())
                val matches = if (f.isDirectory) {
                    f.walkTopDown().filter { it.isFile && it.extension in setOf("txt", "md", "json", "js", "ts", "xml", "html", "csv", "log") }
                        .mapNotNull { file ->
                            val hit = runCatching { file.readLines().firstOrNull { matcher.containsMatchIn(it) } }.getOrNull()
                            if (hit != null) "${file.path}: $hit" else null
                        }.take(100).toList()
                } else emptyList()
                okOp("ok", files = matches)
            }
            "info" -> {
                val f = resolve(str(0))
                if (f == null || !f.exists()) okOp("文件不存在", content = null)
                else buildJsonObject {
                    put("ok", true)
                    put("name", f.name)
                    put("path", f.path)
                    put("type", if (f.isDirectory) "directory" else "file")
                    put("size", f.length())
                    put("lastModified", f.lastModified())
                }
            }
            "download" -> okOp("RikkaHub 脚本环境不提供网络下载回调，无法下载", success = false)
            else -> buildJsonObject {
                put("ok", false)
                put("unavailable", true)
                put("message", "RikkaHub 不支持 Tools.Files.$method")
            }
        }
    }

    // ---------- 参数归一 ----------

    private fun handleMkdir(p: String?): JsonObject {
        val f = resolve(p) ?: return okOp("路径无效")
        val ok = f.exists() || f.mkdirs()
        return okOp(if (ok) "已创建" else "创建失败", path = f.path)
    }

    /** 第一个参数可能是字符串路径，也可能是 { path, environment } 对象，统一取路径 */
    private fun pathArg(el: JsonElement?): String? {
        return when (el) {
            is JsonPrimitive -> el.content
            is JsonObject -> (el["path"] as? JsonPrimitive)?.content
            else -> null
        }
    }

    private fun resolve(p: String?): File? {
        if (p.isNullOrBlank()) return null
        val f = File(root, p.trimStart('/'))
        val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val canonical = runCatching { f.canonicalPath }.getOrNull() ?: return null
        return if (canonical.startsWith(canonicalRoot)) f else null
    }

    private fun okOp(
        message: String,
        content: String? = null,
        path: String? = null,
        success: Boolean = true,
        files: List<String>? = null,
    ): JsonObject {
        return buildJsonObject {
            put("ok", success)
            put("success", success)
            put("message", message)
            if (content != null) put("content", content)
            if (path != null) put("path", path)
            if (files != null) put("files", JsonArray(files.map { JsonPrimitive(it) }))
        }
    }
}