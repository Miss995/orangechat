/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * 工作流终身次数上限（2026-09-23）：workflows 表加 totalRunsCount 列。
 * - 记「这条工作流一共真跑过几次」——同款计数规则：只算 SUCCESS/FAILED，各类 SKIPPED 不算
 * - 与 runsTodayCount 的区别：这个永不按天归零；撞到 maxTotalRuns 时工作流自己把 enabled 置 false
 * - 之所以手写而不是 AutoMigration(30 → 31)：29 → 30 那一版走的是手写迁移、没有导出
 *   30.json 快照，Room 无处比对，自动迁移会直接报 "Schema '30.json' ... was not found"
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(30, 31)
        try {
            db.execSQL(
                "ALTER TABLE workflows ADD COLUMN totalRunsCount INTEGER NOT NULL DEFAULT 0"
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
