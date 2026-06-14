package one.only.player.core.data.repository

import kotlinx.coroutines.flow.Flow
import one.only.player.core.model.FavoriteItem

interface FavoriteRepository {
    fun observeAll(): Flow<List<FavoriteItem>>

    fun observeByParent(parentId: Long?): Flow<List<FavoriteItem>>

    suspend fun getById(id: Long): FavoriteItem?

    suspend fun getByTargetKey(targetKey: String): FavoriteItem?

    suspend fun upsert(item: FavoriteItem): Long

    suspend fun addFolder(
        title: String,
        parentId: Long?,
    ): Long

    suspend fun move(
        ids: List<Long>,
        parentId: Long?,
    )

    suspend fun updateLocalVideoTarget(
        oldLocalUri: String,
        newLocalUri: String,
        newLocalPath: String,
        newTitle: String,
        newMediaStoreId: Long,
    )

    suspend fun updateLocalFolderPath(
        oldPath: String,
        newPath: String,
    )

    suspend fun delete(ids: List<Long>)

    suspend fun clear()
}
