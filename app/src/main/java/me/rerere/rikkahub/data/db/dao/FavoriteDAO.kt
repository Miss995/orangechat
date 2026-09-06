/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.FavoriteEntity

@Dao
interface FavoriteDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("SELECT * FROM favorites ORDER BY created_at DESC")
    fun listAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE type = :type ORDER BY created_at DESC")
    fun listByType(type: String): Flow<List<FavoriteEntity>>

    // 五感记忆库 V1（2026-09-06）：按收藏者查（一次性，heart_query 工具用）
    @Query("SELECT * FROM favorites WHERE owner = :owner ORDER BY created_at DESC")
    suspend fun listByOwner(owner: String): List<FavoriteEntity>

    // 五感记忆库 V1：按收藏者 + 关键词查（模糊匹配 meta/snapshot——理由/五感/预览都搜得到）
    @Query("SELECT * FROM favorites WHERE owner = :owner AND (meta_json LIKE '%' || :keyword || '%' OR snapshot_json LIKE '%' || :keyword || '%' OR ref_json LIKE '%' || :keyword || '%') ORDER BY created_at DESC")
    suspend fun searchByOwner(owner: String, keyword: String): List<FavoriteEntity>

    @Query("SELECT ref_key FROM favorites WHERE type = :type")
    suspend fun getRefKeysByType(type: String): List<String>

    @Query("SELECT substr(ref_key, length('node:' || :conversationId || ':') + 1) FROM favorites WHERE ref_key LIKE 'node:' || :conversationId || ':%'")
    suspend fun getFavoriteNodeIdsOfConversation(conversationId: String): List<String>

    @Query("SELECT * FROM favorites WHERE ref_key = :refKey LIMIT 1")
    suspend fun getByRefKey(refKey: String): FavoriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE ref_key = :refKey)")
    suspend fun existsByRefKey(refKey: String): Boolean

    @Query("DELETE FROM favorites WHERE ref_key = :refKey")
    suspend fun deleteByRefKey(refKey: String): Int

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
