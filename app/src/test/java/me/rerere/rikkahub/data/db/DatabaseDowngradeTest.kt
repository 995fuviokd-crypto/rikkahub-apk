package me.rerere.rikkahub.data.db

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 验证 DatabaseDowngrade 把 v33 库降级为官方 v24 兼容 schema。
 *
 * 构造方式：用 v33 完整 DDL 建库并写入数据 → 执行 downgradeStatements() → 断言
 * 降级后的表集合、memoryentity/workspaces 列、索引集合与官方 v24 一致，
 * 且 user_version / identity_hash 与官方 v24 匹配。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DatabaseDowngradeTest {
    private val v33Ddl = """
        CREATE TABLE IF NOT EXISTS `ConversationEntity` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e', `title` TEXT NOT NULL, `nodes` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, `suggestions` TEXT NOT NULL DEFAULT '[]', `is_pinned` INTEGER NOT NULL DEFAULT 0, `custom_system_prompt` TEXT NOT NULL DEFAULT '', `mode_injection_ids` TEXT NOT NULL DEFAULT '[]', `lorebook_ids` TEXT NOT NULL DEFAULT '[]', `workspace_cwd` TEXT NOT NULL DEFAULT '', `folder_id` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`));
        CREATE INDEX IF NOT EXISTS `index_ConversationEntity_assistant_id` ON `ConversationEntity` (`assistant_id`);
        CREATE INDEX IF NOT EXISTS `index_ConversationEntity_folder_id` ON `ConversationEntity` (`folder_id`);
        CREATE INDEX IF NOT EXISTS `index_ConversationEntity_is_pinned_update_at` ON `ConversationEntity` (`is_pinned`, `update_at`);
        CREATE TABLE IF NOT EXISTS `GenMediaEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, `model_id` TEXT NOT NULL, `prompt` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `type` TEXT NOT NULL DEFAULT 'image_generation', `source_paths` TEXT);
        CREATE INDEX IF NOT EXISTS `index_GenMediaEntity_create_at` ON `GenMediaEntity` (`create_at`);
        CREATE TABLE IF NOT EXISTS `MemoryEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assistant_id` TEXT NOT NULL, `content` TEXT NOT NULL, `target` TEXT NOT NULL, `summary` TEXT, `source` TEXT NOT NULL, `scope_key` TEXT NOT NULL, `conversation_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS `index_MemoryEntity_assistant_id_is_archived` ON `MemoryEntity` (`assistant_id`, `is_archived`);
        CREATE INDEX IF NOT EXISTS `index_MemoryEntity_scope_key` ON `MemoryEntity` (`scope_key`);
        CREATE TABLE IF NOT EXISTS `MemoryJournalEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assistant_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `processed` INTEGER NOT NULL, `digest_memory_id` INTEGER);
        CREATE INDEX IF NOT EXISTS `index_MemoryJournalEntity_processed_assistant_id` ON `MemoryJournalEntity` (`processed`, `assistant_id`);
        CREATE INDEX IF NOT EXISTS `index_MemoryJournalEntity_conversation_id` ON `MemoryJournalEntity` (`conversation_id`);
        CREATE TABLE IF NOT EXISTS `conversation_folder` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, `name` TEXT NOT NULL, `sort_index` INTEGER NOT NULL DEFAULT 0, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`));
        CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` ON `conversation_folder` (`assistant_id`);
        CREATE TABLE IF NOT EXISTS `favorites` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `ref_key` TEXT NOT NULL, `ref_json` TEXT NOT NULL, `snapshot_json` TEXT NOT NULL, `meta_json` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`));
        CREATE UNIQUE INDEX IF NOT EXISTS `index_favorites_ref_key` ON `favorites` (`ref_key`);
        CREATE INDEX IF NOT EXISTS `index_favorites_type` ON `favorites` (`type`);
        CREATE INDEX IF NOT EXISTS `index_favorites_created_at` ON `favorites` (`created_at`);
        CREATE TABLE IF NOT EXISTS `group_messages` (`id` TEXT NOT NULL, `run_id` TEXT NOT NULL, `member_id` TEXT NOT NULL, `member_role` TEXT NOT NULL, `member_model_name` TEXT NOT NULL, `content` TEXT NOT NULL, `kind` TEXT NOT NULL, `reasoning` TEXT NOT NULL, `tools` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`));
        CREATE INDEX IF NOT EXISTS `index_group_messages_run_id` ON `group_messages` (`run_id`);
        CREATE TABLE IF NOT EXISTS `group_runs` (`id` TEXT NOT NULL, `group_id` TEXT NOT NULL, `mission` TEXT NOT NULL, `status` TEXT NOT NULL, `summary` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `started_at` INTEGER NOT NULL, `ended_at` INTEGER NOT NULL, PRIMARY KEY(`id`));
        CREATE INDEX IF NOT EXISTS `index_group_runs_group_id` ON `group_runs` (`group_id`);
        CREATE TABLE IF NOT EXISTS `groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mode` TEXT NOT NULL, `members_json` TEXT NOT NULL, `orchestrator_id` TEXT, `debate_rounds` INTEGER NOT NULL, `reasoning_level` TEXT NOT NULL, `enable_tools` INTEGER NOT NULL, `workspace_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`));
        CREATE INDEX IF NOT EXISTS `index_groups_updated_at` ON `groups` (`updated_at`);
        CREATE TABLE IF NOT EXISTS `managed_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `folder` TEXT NOT NULL, `relative_path` TEXT NOT NULL, `display_name` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL);
        CREATE UNIQUE INDEX IF NOT EXISTS `index_managed_files_relative_path` ON `managed_files` (`relative_path`);
        CREATE INDEX IF NOT EXISTS `index_managed_files_folder` ON `managed_files` (`folder`);
        CREATE TABLE IF NOT EXISTS `message_node` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `node_index` INTEGER NOT NULL, `messages` TEXT NOT NULL, `select_index` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );
        CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` ON `message_node` (`conversation_id`, `node_index`);
        CREATE TABLE IF NOT EXISTS `workflows` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `steps_json` TEXT NOT NULL, `graph_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`));
        CREATE INDEX IF NOT EXISTS `index_workflows_updated_at` ON `workflows` (`updated_at`);
        CREATE TABLE IF NOT EXISTS `workspaces` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `root` TEXT NOT NULL, `shell_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `last_access_at` INTEGER, `tool_approvals` TEXT NOT NULL DEFAULT '{}', `android_local_access` INTEGER NOT NULL DEFAULT 1, `local_directory_uri` TEXT, PRIMARY KEY(`id`));
        CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces` (`root`);
        CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`);
    """.trimIndent()

    private val officialV24Tables = setOf(
        "ConversationEntity",
        "GenMediaEntity",
        "MemoryEntity",
        "conversation_folder",
        "favorites",
        "managed_files",
        "message_node",
        "workspaces",
    )

    private val officialV24Indexes = setOf(
        "index_conversation_folder_assistant_id",
        "index_favorites_ref_key",
        "index_favorites_type",
        "index_favorites_created_at",
        "index_managed_files_relative_path",
        "index_managed_files_folder",
        "index_message_node_conversation_id",
        "index_workspaces_root",
        "index_workspaces_updated_at",
    )

    private fun createTempDb(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "downgrade_${System.nanoTime()}").apply { mkdirs() }
        val file = File(dir, "$name.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        v33Ddl.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
        // 模拟 Room 元数据表与真实数据
        db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, 'v33hash')")
        db.execSQL(
            "INSERT INTO MemoryEntity (id, assistant_id, content, target, summary, source, scope_key, conversation_id, created_at, updated_at, is_archived) " +
                "VALUES (1, 'a1', 'hello', 'MEMORY', NULL, 'manual', 'durable', NULL, 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at, last_access_at, tool_approvals, android_local_access, local_directory_uri) " +
                "VALUES ('w1', 'ws', '/root', '{}', 0, 0, NULL, '{}', 1, NULL)"
        )
        db.execSQL(
            "INSERT INTO workflows (id, name, description, steps_json, graph_json, created_at, updated_at) " +
                "VALUES ('wf1', 'wf', '', '[]', '{}', 0, 0)"
        )
        db.version = 33
        db.close()
        return file
    }

    private fun queryTables(db: SQLiteDatabase): Set<String> =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' AND name != 'room_master_table'",
            null
        ).use { c ->
            val set = mutableSetOf<String>()
            while (c.moveToNext()) set.add(c.getString(0))
            set
        }

    private fun queryIndexes(db: SQLiteDatabase): Set<String> =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'",
            null
        ).use { c ->
            val set = mutableSetOf<String>()
            while (c.moveToNext()) set.add(c.getString(0))
            set
        }

    private fun queryColumns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
            val set = mutableSetOf<String>()
            while (c.moveToNext()) set.add(c.getString(1))
            set
        }

    @Test
    fun downgrade_v33_to_official_v24_schema() {
        val dbFile = createTempDb("downgrade_test")

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        DatabaseDowngrade.downgradeStatements().forEach { db.execSQL(it) }
        val version = db.version
        val identityHash = db.rawQuery(
            "SELECT identity_hash FROM room_master_table WHERE id = 42",
            null
        ).use { c ->
            c.moveToFirst()
            c.getString(0)
        }

        // 1. schema version 与 identity hash 回退到官方 v24
        assertEquals(DatabaseDowngrade.OFFICIAL_SCHEMA_VERSION, version)
        assertEquals(DatabaseDowngrade.OFFICIAL_IDENTITY_HASH, identityHash)

        // 2. 表集合与官方 v24 一致，无残留 v33 独有表
        val tables = queryTables(db)
        assertEquals(officialV24Tables, tables)

        // 3. 索引集合与官方 v24 一致
        val indexes = queryIndexes(db)
        assertEquals(officialV24Indexes, indexes)

        // 4. memoryentity 只有官方三列，数据保留
        assertEquals(setOf("id", "assistant_id", "content"), queryColumns(db, "MemoryEntity"))
        val memoryContent = db.rawQuery("SELECT content FROM MemoryEntity WHERE id = 1", null).use { c ->
            c.moveToFirst()
            c.getString(0)
        }
        assertEquals("hello", memoryContent)

        // 5. workspaces 只有官方列，数据保留
        assertEquals(
            setOf("id", "name", "root", "shell_status", "created_at", "updated_at", "last_access_at", "tool_approvals"),
            queryColumns(db, "workspaces")
        )
        val workspaceName = db.rawQuery("SELECT name FROM workspaces WHERE id = 'w1'", null).use { c ->
            c.moveToFirst()
            c.getString(0)
        }
        assertEquals("ws", workspaceName)

        db.close()
        dbFile.delete()
    }

    @Test
    fun downgrade_preserves_other_official_tables() {
        val dbFile = createTempDb("downgrade_keep")

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        DatabaseDowngrade.downgradeStatements().forEach { db.execSQL(it) }

        // 官方 v24 表数据仍可访问
        db.rawQuery("SELECT COUNT(*) FROM ConversationEntity", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.rawQuery("SELECT COUNT(*) FROM message_node", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        // v33 独有表已不存在
        assertFalse(tablesContain(db, "workflows"))
        assertFalse(tablesContain(db, "groups"))
        assertFalse(tablesContain(db, "group_runs"))
        assertFalse(tablesContain(db, "group_messages"))
        assertFalse(tablesContain(db, "MemoryJournalEntity"))
        assertFalse(tablesContain(db, "memoryjournalentity"))
        assertFalse(tablesContain(db, "memory_fts"))

        db.close()
        dbFile.delete()
    }

    private fun tablesContain(db: SQLiteDatabase, table: String): Boolean =
        queryTables(db).contains(table)
}
