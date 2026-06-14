package one.only.player.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import one.only.player.core.database.entities.FavoriteItemEntity

@Dao
interface FavoriteItemDao {

    @Query("SELECT * FROM favorite_item ORDER BY parent_id ASC, sort_order ASC, title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FavoriteItemEntity>>

    @Query("SELECT * FROM favorite_item ORDER BY parent_id ASC, sort_order ASC, title COLLATE NOCASE ASC")
    suspend fun getAll(): List<FavoriteItemEntity>

    @Query(
        """
        SELECT * FROM favorite_item
        WHERE (:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId
        ORDER BY sort_order ASC, title COLLATE NOCASE ASC
        """,
    )
    fun observeByParent(parentId: Long?): Flow<List<FavoriteItemEntity>>

    @Query("SELECT * FROM favorite_item WHERE id = :id")
    suspend fun getById(id: Long): FavoriteItemEntity?

    @Query("SELECT * FROM favorite_item WHERE target_key = :targetKey")
    suspend fun getByTargetKey(targetKey: String): FavoriteItemEntity?

    @Query("SELECT * FROM favorite_item WHERE target_type = :targetType AND local_uri = :localUri")
    suspend fun getByTargetTypeAndLocalUri(
        targetType: String,
        localUri: String,
    ): FavoriteItemEntity?

    @Query("SELECT * FROM favorite_item WHERE target_type = :targetType")
    suspend fun getByTargetType(targetType: String): List<FavoriteItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FavoriteItemEntity): Long

    @Update
    suspend fun update(entity: FavoriteItemEntity)

    @Query("UPDATE favorite_item SET parent_id = :parentId, updated_at = :updatedAt, sort_order = :sortOrder WHERE id in (:ids)")
    suspend fun move(
        ids: List<Long>,
        parentId: Long?,
        updatedAt: Long,
        sortOrder: Long,
    )

    @Query("DELETE FROM favorite_item WHERE id in (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM favorite_item")
    suspend fun clear()
}
