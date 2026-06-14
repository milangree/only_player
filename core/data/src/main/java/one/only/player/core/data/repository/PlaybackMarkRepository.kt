package one.only.player.core.data.repository

import kotlinx.coroutines.flow.Flow
import one.only.player.core.model.PlaybackMark

interface PlaybackMarkRepository {
    fun observeAll(): Flow<List<PlaybackMark>>

    fun observeByMediaUri(mediaUri: String): Flow<List<PlaybackMark>>

    suspend fun getById(id: Long): PlaybackMark?

    suspend fun getByMediaUri(mediaUri: String): List<PlaybackMark>

    suspend fun add(mark: PlaybackMark): Long

    suspend fun deleteById(id: Long)

    suspend fun deleteByMediaUri(mediaUri: String)

    suspend fun clear()

    suspend fun updateMediaUri(
        oldMediaUri: String,
        newMediaUri: String,
    )
}
