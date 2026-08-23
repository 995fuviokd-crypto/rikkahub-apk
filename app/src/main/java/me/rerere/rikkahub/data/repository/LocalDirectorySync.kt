package me.rerere.rikkahub.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF（Storage Access Framework）本地目录同步器。
 *
 * 把用户通过系统目录选择器（ACTION_OPEN_DOCUMENT_TREE）授权的本地目录与 App 内部
 * 镜像目录双向同步：
 * - [syncToMirror]：命令执行前把本地目录最新内容拉取到镜像，供 shell 通过 /local 读写；
 * - [syncMirrorBack]：命令执行后把镜像中新增/变更的文件写回本地目录。
 *
 * 通过 ContentResolver 走系统文件提供方，不依赖 MANAGE_EXTERNAL_STORAGE，
 * 任何目录（Download、Documents、任意文件夹）授权后都可读写。
 */
object LocalDirectorySync {
    private const val TAG = "LocalDirectorySync"

    fun hasPersistedPermission(context: Context, treeUri: Uri): Boolean {
        val flags = try {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission || it.isWritePermission }
                .map { it.uri }
        } catch (e: Throwable) {
            Log.w(TAG, "read persisted permissions failed", e)
            return false
        }
        return flags.any { it.toString().startsWith(treeUri.toString()) }
    }

    /**
     * 拉取本地目录 -> 镜像目录。只复制缺失或内容变化（大小或最后修改时间不同）的文件。
     */
    suspend fun syncToMirror(
        context: Context,
        treeUri: Uri,
        mirrorDir: File,
        maxBytesPerFile: Long = 64L * 1024 * 1024,
    ) = withContext(Dispatchers.IO) {
        if (!mirrorDir.exists()) mirrorDir.mkdirs()
        val resolver = context.contentResolver
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            syncTreeToDir(resolver, treeUri, rootDocId, mirrorDir, maxBytesPerFile)
        } catch (e: Throwable) {
            Log.e(TAG, "syncToMirror failed: $treeUri -> $mirrorDir", e)
            throw e
        }
    }

    /**
     * 推回镜像目录 -> 本地目录。本地不存在的文件会新建，内容变化会覆盖写回。
     */
    suspend fun syncMirrorBack(
        context: Context,
        treeUri: Uri,
        mirrorDir: File,
    ) = withContext(Dispatchers.IO) {
        if (!mirrorDir.exists()) return@withContext
        val resolver = context.contentResolver
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            syncDirToTree(resolver, treeUri, rootDocId, mirrorDir)
        } catch (e: Throwable) {
            Log.e(TAG, "syncMirrorBack failed: $mirrorDir -> $treeUri", e)
            throw e
        }
    }

    private fun syncTreeToDir(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        docId: String,
        targetDir: File,
        maxBytesPerFile: Long,
    ) {
        // 迭代式深度优先遍历，避免目录层级过深导致递归栈溢出
        val stack = ArrayDeque<Pair<String, File>>()
        stack.addLast(docId to targetDir)
        while (stack.isNotEmpty()) {
            val (currentId, dir) = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentId)
            val cursor = resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            ) ?: continue

            cursor.use {
                while (it.moveToNext()) {
                    val childId = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    if (name.startsWith(".l2s.")) continue
                    val mime = it.getString(2) ?: break
                    val size = if (it.isNull(3)) -1L else it.getLong(3)
                    val lastModified = if (it.isNull(4)) 0L else it.getLong(4)
                    val target = File(dir, name)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        target.mkdirs()
                        stack.addLast(childId to target)
                    } else {
                        val upToDate = target.isFile &&
                            (size < 0 || target.length() == size) &&
                            (lastModified <= 0 || target.lastModified() >= lastModified - 1_000L)
                        if (!upToDate) {
                            if (size > maxBytesPerFile) {
                                Log.w(TAG, "skip oversized file: $name (${size}b > ${maxBytesPerFile}b)")
                                continue
                            }
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            try {
                                resolver.openInputStream(docUri)?.use { input ->
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { output -> input.copyTo(output) }
                                } ?: Log.w(TAG, "openInputStream null: $name")
                            } catch (e: FileNotFoundException) {
                                Log.w(TAG, "skip unreadable file: $name", e)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun syncDirToTree(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        docId: String,
        sourceDir: File,
    ) {
        // 迭代式深度优先遍历，避免目录层级过深导致递归栈溢出
        val stack = ArrayDeque<Triple<String, File, String>>()
        stack.addLast(Triple(docId, sourceDir, docId))
        while (stack.isNotEmpty()) {
            val (parentId, dir, _) = stack.removeLast()
            val children = dir.listFiles()?.toList().orEmpty()
            for (file in children) {
                if (file.name.startsWith(".l2s.")) continue
                val existing = findDocument(resolver, treeUri, parentId, file.name)
                if (file.isDirectory) {
                    val dirId = existing ?: createDocument(
                        resolver, treeUri, parentId,
                        DocumentsContract.Document.MIME_TYPE_DIR, file.name,
                    ) ?: continue
                    stack.addLast(Triple(dirId, file, parentId))
                } else {
                    val mime = file.extension.toMimeType() ?: "application/octet-stream"
                    val docUri = if (existing != null) {
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, existing)
                    } else {
                        createDocument(resolver, treeUri, parentId, mime, file.name)?.let {
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, it)
                        } ?: continue
                    }
                    val upToDate = try {
                        val cur = resolver.query(
                            docUri,
                            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                            null, null, null,
                        )
                        var same = false
                        cur?.use {
                            if (it.moveToFirst() && !it.isNull(0)) {
                                same = it.getLong(0) == file.length()
                            }
                        }
                        same
                    } catch (e: Throwable) {
                        false
                    }
                    if (!upToDate) {
                        try {
                            resolver.openOutputStream(docUri, "wt")?.use { output ->
                                file.inputStream().use { input -> input.copyTo(output) }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "write back failed: ${file.name}", e)
                        }
                    }
                }
            }
        }
    }

    private fun findDocument(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        name: String,
    ): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        ) ?: return null
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val displayName = it.getString(1) ?: continue
                if (displayName == name) return id
            }
        }
        return null
    }

    private fun createDocument(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        mime: String,
        name: String,
    ): String? = runCatching {
        DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId),
            mime,
            name,
        )?.let { DocumentsContract.getDocumentId(it) }
    }.getOrNull()

    private fun String.toMimeType(): String? = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(this.lowercase())
}
