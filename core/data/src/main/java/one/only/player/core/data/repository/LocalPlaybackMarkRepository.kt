package one.only.player.core.data.repository

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import one.only.player.core.database.dao.PlaybackMarkDao
import one.only.player.core.database.entities.PlaybackMarkEntity
import one.only.player.core.model.PlaybackMark

class LocalPlaybackMarkRepository @Inject constructor(
    private val dao: PlaybackMarkDao,
) : PlaybackMarkRepository {

    override fun observeAll(): Flow<List<PlaybackMark>> = dao.observeAll().map { entities -> entities.map { it.toModel() } }

    override fun observeByMediaUri(mediaUri: String): Flow<List<PlaybackMark>> = dao.observeByMediaUri(mediaUri).map { entities ->
        entities.map { it.toModel() }
    }

    override suspend fun getById(id: Long): PlaybackMark? = dao.getById(id)?.toModel()

    override suspend fun getByMediaUri(mediaUri: String): List<PlaybackMark> = dao.getByMediaUri(mediaUri).map { it.toModel() }

    override suspend fun add(mark: PlaybackMark): Long = dao.insert(mark.toEntity())

    override suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun deleteByMediaUri(mediaUri: String) {
        dao.deleteByMediaUri(mediaUri)
    }

    override suspend fun clear() {
        dao.clear()
    }

    override suspend fun updateMediaUri(
        oldMediaUri: String,
        newMediaUri: String,
    ) {
        if (oldMediaUri == newMediaUri) return
        dao.updateMediaUri(
            oldMediaUri = oldMediaUri,
            newMediaUri = newMediaUri,
        )
    }
}

private fun PlaybackMarkEntity.toModel(): PlaybackMark = PlaybackMark(
    id = id,
    mediaUri = mediaUri,
    positionMs = positionMs,
    durationMs = durationMs,
    label = label,
    createdAt = createdAt,
)

private fun PlaybackMark.toEntity(): PlaybackMarkEntity = PlaybackMarkEntity(
    id = id,
    mediaUri = mediaUri,
    positionMs = positionMs,
    durationMs = durationMs,
    label = label,
    createdAt = createdAt,
)
