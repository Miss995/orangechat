/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["ref_key"], unique = true),
        Index(value = ["type"]),
        Index(value = ["owner"]),
        Index(value = ["created_at"])
    ]
)
data class FavoriteEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("type")
    val type: String,
    @ColumnInfo("owner")
    val owner: String = FavoriteOwner.USER,
    @ColumnInfo("ref_key")
    val refKey: String,
    @ColumnInfo("ref_json")
    val refJson: String,
    @ColumnInfo("snapshot_json")
    val snapshotJson: String,
    @ColumnInfo("meta_json")
    val metaJson: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

/**
 * 收藏者（2026-09-06 五感记忆库 V1）：区分这条收藏是宝收的还是橘仔收的。
 * - USER = 宝（人）收的，通过聊天界面收藏按钮收藏
 * - AI = 橘仔收的，通过 heart_save 工具收藏（带理由+五感）
 */
object FavoriteOwner {
    const val USER = "user"
    const val AI = "ai"
}
