package me.rerere.rikkahub.data.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

internal val partTypeMapping = mapOf(
    "Text" to "text",
    "UIMessagePart.Text" to "text",
    "me.rerere.ai.ui.UIMessagePart.Text" to "text",
    "Image" to "image",
    "UIMessagePart.Image" to "image",
    "me.rerere.ai.ui.UIMessagePart.Image" to "image",
    "Video" to "video",
    "UIMessagePart.Video" to "video",
    "me.rerere.ai.ui.UIMessagePart.Video" to "video",
    "Audio" to "audio",
    "UIMessagePart.Audio" to "audio",
    "me.rerere.ai.ui.UIMessagePart.Audio" to "audio",
    "Document" to "document",
    "UIMessagePart.Document" to "document",
    "me.rerere.ai.ui.UIMessagePart.Document" to "document",
    "Reasoning" to "reasoning",
    "UIMessagePart.Reasoning" to "reasoning",
    "me.rerere.ai.ui.UIMessagePart.Reasoning" to "reasoning",
    "Search" to "search",
    "UIMessagePart.Search" to "search",
    "me.rerere.ai.ui.UIMessagePart.Search" to "search",
    "ToolCall" to "tool_call",
    "UIMessagePart.ToolCall" to "tool_call",
    "me.rerere.ai.ui.UIMessagePart.ToolCall" to "tool_call",
    "ToolResult" to "tool_result",
    "UIMessagePart.ToolResult" to "tool_result",
    "me.rerere.ai.ui.UIMessagePart.ToolResult" to "tool_result",
    "Tool" to "tool",
    "UIMessagePart.Tool" to "tool",
    "me.rerere.ai.ui.UIMessagePart.Tool" to "tool",
)

internal fun migrateMessagesJson(messagesJson: String): String {
    return runCatching {
        val element = JsonInstant.parseToJsonElement(messagesJson)
        val migrated = migrateMessagesElement(element)
        if (migrated == element) messagesJson else JsonInstant.encodeToString(migrated)
    }.getOrElse { messagesJson }
}

internal fun migrateMessagesElement(element: JsonElement): JsonElement {
    val rootArray = element as? JsonArray ?: return element
    val migratedArray = JsonArray(
        rootArray.map { message ->
            val messageObject = message as? JsonObject ?: return@map message
            val partsElement = messageObject["parts"] as? JsonArray ?: return@map message
            val migratedParts = migratePartsArray(partsElement)
            if (migratedParts == partsElement) {
                message
            } else {
                JsonObject(messageObject.toMutableMap().apply {
                    put("parts", migratedParts)
                })
            }
        }
    )
    return if (migratedArray == rootArray) element else migratedArray
}

internal fun migratePartsArray(partsElement: JsonArray): JsonArray {
    return JsonArray(
        partsElement.map { part ->
            val partObject = part as? JsonObject ?: return@map part
            val typeValue = partObject["type"]?.jsonPrimitiveOrNull?.contentOrNull
            val mappedType = typeValue?.let { partTypeMapping[it] } ?: typeValue

            var updatedPart: JsonElement = part
            if (mappedType != null && mappedType != typeValue) {
                updatedPart = JsonObject(partObject.toMutableMap().apply {
                    put("type", JsonPrimitive(mappedType))
                })
            }

            val updatedObject = updatedPart as? JsonObject ?: return@map updatedPart
            val outputElement = updatedObject["output"] as? JsonArray ?: return@map updatedPart
            val migratedOutput = migratePartsArray(outputElement)
            if (migratedOutput == outputElement) {
                updatedPart
            } else {
                JsonObject(updatedObject.toMutableMap().apply {
                    put("output", migratedOutput)
                })
            }
        }
    )
}

/**
 * 确保 workflows 表结构与 [me.rerere.rikkahub.data.db.entity.WorkflowEntity] 完全一致：
 * 全部字段 NOT NULL + 显式索引 index_workflows_updated_at。
 *
 * 兼容以下历史状态：
 * - 表不存在：直接创建正确结构。
 * - v2.4.12 旧迁移 `ADD COLUMN graph_json TEXT` 造成的错误结构
 *   （graph_json nullable、索引名 index_WorkflowEntity_updated_at）：重建修复。
 * - 缺失 graph_json/steps_json/description 列的更早结构：用默认值兜底迁移。
 *
 * 该函数幂等：结构已正确时不重建。30→31 与 31→32 迁移都会调用，
 * 以修复已在 v31 错误结构上提交（v2.4.12 崩溃用户）的数据库。
 */
internal fun ensureWorkflowsTable(db: SupportSQLiteDatabase) {
    val columns = db.query("PRAGMA table_info(`workflows`)").use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                put(name, notNull)
            }
        }
    }
    if (columns.isNotEmpty() && isCorrectWorkflowsTable(db, columns)) return

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `workflows_new` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `description` TEXT NOT NULL,
            `steps_json` TEXT NOT NULL,
            `graph_json` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent()
    )

    if (columns.isNotEmpty()) {
        val selectExprs = listOf(
            "id",
            "name",
            if ("description" in columns) "COALESCE(`description`, '')" else "''",
            if ("steps_json" in columns) "COALESCE(`steps_json`, '[]')" else "'[]'",
            if ("graph_json" in columns) "COALESCE(`graph_json`, '{}')" else "'{}'",
            "created_at",
            "updated_at",
        ).joinToString(", ")
        db.execSQL(
            """
            INSERT INTO `workflows_new` (`id`, `name`, `description`, `steps_json`, `graph_json`, `created_at`, `updated_at`)
            SELECT $selectExprs FROM `workflows`
            """.trimIndent()
        )
    }

    db.execSQL("DROP TABLE IF EXISTS `workflows`")
    db.execSQL("ALTER TABLE `workflows_new` RENAME TO `workflows`")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflows_updated_at` ON `workflows` (`updated_at`)")
}

private fun isCorrectWorkflowsTable(db: SupportSQLiteDatabase, columns: Map<String, Boolean>): Boolean {
    val requiredColumns = listOf(
        "id", "name", "description", "steps_json", "graph_json", "created_at", "updated_at",
    )
    if (requiredColumns.any { columns[it] != true }) return false
    val indexExists = db.query("PRAGMA index_list(`workflows`)").use { cursor ->
        var found = false
        while (cursor.moveToNext()) {
            if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_workflows_updated_at") {
                found = true
                break
            }
        }
        found
    }
    return indexExists
}
