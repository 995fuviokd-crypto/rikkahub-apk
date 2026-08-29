package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.Navigator
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navigator: Navigator,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    nodeId: Uuid? = null,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navigator.clearAndNavigate(
        Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
        )
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

private val ALLOWED_MIME_TYPES = setOf(
    "text/plain", "text/html", "text/css", "text/javascript", "text/csv", "text/xml",
    "application/json", "application/javascript", "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/epub+zip"
)

private val ALLOWED_FILE_EXTENSIONS = setOf(
    // 纯文本/标记
    "txt", "md", "markdown", "mdx", "csv", "log", "out",
    "html", "htm", "css", "scss", "less", "styl", "xml",
    // 代码
    "json", "js", "jsx", "mjs", "cjs", "ts", "tsx",
    "py", "rb", "lua", "sql", "java", "kt", "kts", "dart", "php", "swift", "go",
    "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh", "fish",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx", "rs", "cs",
    "proto", "graphql", "gql", "yml", "yaml",
    // 构建/配置
    "gradle", "toml", "ini", "env", "properties",
    "mk", "bp", "bazel", "bzl", "rc", "prop", "conf", "cfg", "sums",
    "patch", "diff", "gitignore", "editorconfig",
    // Android 相机配置
    "agc",
    // 文档
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "epub", "odt", "rtf",
    // 归档/固件/安装包
    "zip", "tar", "gz", "tgz", "xz", "bz2", "7z", "rar", "jar", "war",
    "apk", "whl", "deb", "rpm", "iso", "img",
    // 证书/密钥
    "pem", "crt", "key",
    // 笔记/数据
    "ipynb", "db", "sqlite",
)

fun isAllowedFileType(fileName: String, mime: String): Boolean {
    if (mime in ALLOWED_MIME_TYPES || mime.startsWith("text/")) return true
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in ALLOWED_FILE_EXTENSIONS
}
