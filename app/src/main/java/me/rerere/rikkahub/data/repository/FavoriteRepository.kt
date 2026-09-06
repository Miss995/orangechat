/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkahub.data.model.FavoriteType
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import kotlin.uuid.Uuid

class FavoriteRepository(
    private val dao: FavoriteDAO,
) {
    fun listAll(): Flow<List<FavoriteEntity>> = dao.listAll()

    fun listByType(type: FavoriteType): Flow<List<FavoriteEntity>> = dao.listByType(type.value)

    suspend fun getByRefKey(refKey: String): FavoriteEntity? = dao.getByRefKey(refKey)

    suspend fun existsByRefKey(refKey: String): Boolean = dao.existsByRefKey(refKey)

    suspend fun deleteByRefKey(refKey: String): Int = dao.deleteByRefKey(refKey)

    suspend fun deleteById(id: String): Int = dao.deleteById(id)

    suspend fun upsert(entity: FavoriteEntity) = dao.upsert(entity)

    suspend fun addNodeFavorite(target: NodeFavoriteTarget): FavoriteEntity {
        val refKey = NodeFavoriteAdapter.buildRefKey(target)
        val existing = dao.getByRefKey(refKey)
        val favorite = NodeFavoriteAdapter.buildFavoriteEntity(
            target = target,
            existing = existing,
        )
        dao.upsert(favorite)
        return favorite
    }

    // 五感记忆库 V1（2026-09-06）：橘仔收藏（heart_save 工具）——owner=ai + 理由 + 五感
    suspend fun addAiNodeFavorite(
        target: NodeFavoriteTarget,
        reason: String,
        senses: List<String>,
    ): FavoriteEntity {
        val refKey = NodeFavoriteAdapter.buildRefKey(target)
        val existing = dao.getByRefKey(refKey)
        val favorite = NodeFavoriteAdapter.buildAiFavoriteEntity(
            target = target,
            existing = existing,
            reason = reason,
            senses = senses,
        )
        dao.upsert(favorite)
        return favorite
    }

    // 五感记忆库 V1：按收藏者查（heart_query 工具用）
    suspend fun listByOwner(owner: String, keyword: String? = null): List<FavoriteEntity> {
        return if (keyword.isNullOrBlank()) dao.listByOwner(owner)
        else dao.searchByOwner(owner, keyword)
    }

    suspend fun removeNodeFavorite(conversationId: Uuid, nodeId: Uuid): Int {
        return dao.deleteByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }

    suspend fun isNodeFavorited(conversationId: Uuid, nodeId: Uuid): Boolean {
        return dao.existsByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }
}
