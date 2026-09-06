/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.data.db.entity.FavoriteOwner

/**
 * 五感记忆库 V1（2026-09-06 宝拍板）：favorites 表加 owner 列，区分收藏者。
 * - user = 宝收的（存量收藏全部归 user，通过聊天界面收藏按钮收藏）
 * - ai = 橘仔收的（通过 heart_save 工具收藏，带理由 + 五感）
 */
val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(29, 30)
        try {
            db.execSQL(
                "ALTER TABLE favorites ADD COLUMN owner TEXT NOT NULL DEFAULT '${FavoriteOwner.USER}'"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_owner ON favorites(owner)")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
