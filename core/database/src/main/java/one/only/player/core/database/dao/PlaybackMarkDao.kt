package one.only.player.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import one.only.player.core.database.entities.PlaybackMarkEntity

@Dao
interface PlaybackMarkDao {

    @Query("SELECT * FROM playback_mark ORDER BY created_at DESC")
    fun observeAll(): Flow<List<PlaybackMarkEntity>>

    @Query("SELECT * FROM playback_mark WHERE media_uri = :mediaUri ORDER BY position_ms ASC, created_at ASC")
    fun observeByMediaUri(mediaUri: String): Flow<List<PlaybackMarkEntity>>

    @Query("SELECT * FROM playback_mark WHERE media_uri = :mediaUri ORDER BY position_ms ASC, created_at ASC")
    suspend fun getByMediaUri(mediaUri: String): List<PlaybackMarkEntity>

    @Query("SELECT * FROM playback_mark WHERE id = :id")
    suspend fun getById(id: Long): PlaybackMarkEntity?

    @Insert
    suspend fun insert(entity: PlaybackMarkEntity): Long

    @Query("DELETE FROM playback_mark WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM playback_mark WHERE media_uri = :mediaUri")
    suspend fun deleteByMediaUri(mediaUri: String)

    @Query("DELETE FROM playback_mark")
    suspend fun clear()

    @Query("UPDATE playback_mark SET media_uri = :newMediaUri WHERE media_uri = :oldMediaUri")
    suspend fun updateMediaUri(
        oldMediaUri: String,
        newMediaUri: String,
    )
}
