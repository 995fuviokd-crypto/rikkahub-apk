package me.rerere.rikkahub.data.db

import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * 官方版（原版 RikkaHub）与修改版数据库 schema 降级工具。
 *
 * 修改版在官方版 v24 基础上迭代升级数据库到 v33：
 * - v25: memoryentity 加 8 列，新增 memoryjournalentity 表、memory_fts 虚拟表
 * - v26: workspaces 加 android_local_access 列
 * - v27: 索引变更（conversationentity/genmediaentity/message_node/memoryentity）
 * - v28: 新增 workflows 表
 * - v29-v30: workspaces 加 local_directory_uri 列并重建表
 * - v32: 新增 groups/group_runs/group_messages 表
 * - v33: memoryjournalentity 重命名为 MemoryJournalEntity
 *
 * 官方版只能打开 v24 数据库，导入修改版 v33 备份会因 schema 不兼容崩溃。
 * 因此导出备份时，把数据库降级为官方版 v24 兼容副本。
 *
 * 降级策略：删除 v33 独有表；对共有表（memoryentity/workspaces）采用「重建表」
 * 的方式剔除 v33 新增列，兼容 SQLite 3.35 以下不支持 DROP COLUMN 的环境；
 * 索引按官方 v24 清单重建。全部 SQL 提取为 [downgradeStatements]，测试可复用。
 */
object DatabaseDowngrade {
    private const val TAG = "DatabaseDowngrade"

    // 官方版 schema version 与其 identity hash（与官方版 AppDatabase_Impl 一致）
    internal const val OFFICIAL_SCHEMA_VERSION = 24
    internal const val OFFICIAL_IDENTITY_HASH = "0ea1aaebfa031c7995c45a1e35822e1a"

    // 官方 v24 共有表在 v33 中新增的列（降级时剔除）
    private const val MEMORY_ENTITY_TABLE = "MemoryEntity"
    private const val MEMORY_ENTITY_KEEP_COLUMNS = "`id`, `assistant_id`, `content`"
    private const val MEMORY_ENTITY_OFFICIAL_DDL =
        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`assistant_id` TEXT NOT NULL, " +
            "`content` TEXT NOT NULL)"
    private const val WORKSPACES_KEEP_COLUMNS =
        "`id`, `name`, `root`, `shell_status`, `created_at`, " +
            "`updated_at`, `last_access_at`, `tool_approvals`"
    private const val WORKSPACES_OFFICIAL_DDL =
        "(`id` TEXT NOT NULL, `name` TEXT NOT NULL, `root` TEXT NOT NULL, " +
            "`shell_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
            "`updated_at` INTEGER NOT NULL, `last_access_at` INTEGER, " +
            "`tool_approvals` TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(`id`))"

    // v33 独有、官方 v24 不存在的表，导出时必须移除
    private val EXTRA_TABLES = listOf(
        "memory_fts",
        "MemoryJournalEntity",
        "memoryjournalentity",
        "workflows",
        "groups",
        "group_runs",
        "group_messages",
    )

    // v27 新增、官方 v24 不存在的索引，导出时必须移除
    private val EXTRA_INDEXES = listOf(
        "index_MemoryEntity_scope_key",
        "index_MemoryEntity_assistant_id_is_archived",
        "index_ConversationEntity_assistant_id",
        "index_ConversationEntity_folder_id",
        "index_ConversationEntity_is_pinned_update_at",
        "index_GenMediaEntity_create_at",
        "index_message_node_conversation_id_node_index",
    )

    // 官方 v24 在 v27 被替换/需保证存在的索引
    private const val OFFICIAL_MESSAGE_NODE_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id` " +
            "ON `message_node` (`conversation_id`)"
    private const val OFFICIAL_WORKSPACES_ROOT_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` " +
            "ON `workspaces` (`root`)"
    private const val OFFICIAL_WORKSPACES_UPDATED_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` " +
            "ON `workspaces` (`updated_at`)"

    /**
     * 生成官方版 v24 兼容的数据库副本。
     *
     * @param sourceDb 已 checkpoint 的主数据库文件（rikka_hub.db）
     * @param targetDir 目标临时目录
     * @return 降级后的数据库文件，失败返回 null（调用方应回退到原始导出）
     */
    fun createDowngradedCopy(sourceDb: File, targetDir: File): File? {
        if (!sourceDb.exists()) return null
        val targetDb = File(targetDir, "rikka_hub.db")
        return try {
            sourceDb.copyTo(targetDb, overwrite = true)

            val db = SQLiteDatabase.openDatabase(
                targetDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            db.use {
                // 转成非 WAL 模式，确保降级数据全部落盘到 db 文件，关闭后无 wal/shm 残留
                it.rawQuery("PRAGMA journal_mode = DELETE", null).use { c -> c.moveToFirst() }
                downgradeStatements().forEach { sql -> it.execSQL(sql) }
            }

            Log.i(TAG, "createDowngradedCopy: downgraded to v$OFFICIAL_SCHEMA_VERSION")
            targetDb
        } catch (e: Exception) {
            Log.e(TAG, "createDowngradedCopy: failed to downgrade database", e)
            runCatching { targetDb.delete() }
            null
        }
    }

    /**
     * 降级为官方 v24 所需执行的完整 SQL 序列。
     * 生产代码用 requery 执行，测试用 framework SQLite 执行以验证 schema 一致性。
     */
    internal fun downgradeStatements(): List<String> {
        val statements = mutableListOf<String>()

        // 1. 删除 v33 独有表（含记忆系统、群组、工作流），其索引随表删除
        EXTRA_TABLES.forEach { table ->
            statements += "DROP TABLE IF EXISTS `$table`"
        }

        // 2. 删除 v27 新增索引（其中引用 memoryentity 待删列）
        EXTRA_INDEXES.forEach { index ->
            statements += "DROP INDEX IF EXISTS `$index`"
        }

        // 3. memoryentity 重建为官方 v24 三列结构
        statements += "DROP TABLE IF EXISTS `memoryentity_new`"
        statements += "CREATE TABLE `memoryentity_new` $MEMORY_ENTITY_OFFICIAL_DDL"
        statements +=
            "INSERT INTO `memoryentity_new` ($MEMORY_ENTITY_KEEP_COLUMNS) " +
                "SELECT $MEMORY_ENTITY_KEEP_COLUMNS FROM `$MEMORY_ENTITY_TABLE`"
        statements += "DROP TABLE `$MEMORY_ENTITY_TABLE`"
        statements += "ALTER TABLE `memoryentity_new` RENAME TO `$MEMORY_ENTITY_TABLE`"

        // 4. workspaces 重建为官方 v24 结构
        statements += "DROP TABLE IF EXISTS `workspaces_new`"
        statements += "CREATE TABLE `workspaces_new` $WORKSPACES_OFFICIAL_DDL"
        statements +=
            "INSERT INTO `workspaces_new` ($WORKSPACES_KEEP_COLUMNS) " +
                "SELECT $WORKSPACES_KEEP_COLUMNS FROM `workspaces`"
        statements += "DROP TABLE `workspaces`"
        statements += "ALTER TABLE `workspaces_new` RENAME TO `workspaces`"

        // 5. 重建官方 v24 索引
        statements += OFFICIAL_WORKSPACES_ROOT_INDEX
        statements += OFFICIAL_WORKSPACES_UPDATED_INDEX
        statements += OFFICIAL_MESSAGE_NODE_INDEX

        // 6. 回退 schema version 与 identity hash，使官方版 Room 校验通过
        statements += "PRAGMA user_version = $OFFICIAL_SCHEMA_VERSION"
        statements +=
            "UPDATE room_master_table SET identity_hash = '$OFFICIAL_IDENTITY_HASH' WHERE id = 42"

        return statements
    }
}
