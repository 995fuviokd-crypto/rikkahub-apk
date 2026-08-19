package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v30 → v31：引入 graph_json 列 / 规范化 workflows 表。
 *
 * 注意：v2.4.12 曾用 `ALTER TABLE workflows ADD COLUMN graph_json TEXT` 实现本迁移，
 * 该实现会产生 nullable 列且保留旧索引 index_WorkflowEntity_updated_at，
 * 与实体（NOT NULL + index_workflows_updated_at）不一致，导致 Room 校验失败崩溃。
 * 这里统一走 [ensureWorkflowsTable] 重建，兼容表不存在 / 缺列 / 错误结构的各种状态。
 */
object Migration_30_31 : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureWorkflowsTable(db)
    }
}
