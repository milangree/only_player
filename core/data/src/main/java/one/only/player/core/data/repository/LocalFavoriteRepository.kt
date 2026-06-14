package one.only.player.core.data.repository

import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import one.only.player.core.common.extensions.canonicalPathOrSelf
import one.only.player.core.common.extensions.prettyName
import one.only.player.core.database.dao.FavoriteItemDao
import one.only.player.core.database.entities.FavoriteItemEntity
import one.only.player.core.model.FavoriteItem
import one.only.player.core.model.FavoriteTargetType
import one.only.player.core.model.ServerProtocol

class LocalFavoriteRepository @Inject constructor(
    private val dao: FavoriteItemDao,
) : FavoriteRepository {

    override fun observeAll(): Flow<List<FavoriteItem>> = dao.observeAll().map { entities -> entities.map { it.toModel() } }

    override fun observeByParent(parentId: Long?): Flow<List<FavoriteItem>> = dao.observeByParent(parentId).map { entities ->
        entities.map { it.toModel() }
    }

    override suspend fun getById(id: Long): FavoriteItem? = dao.getById(id)?.toModel()

    override suspend fun getByTargetKey(targetKey: String): FavoriteItem? = dao.getByTargetKey(targetKey)?.toModel()

    override suspend fun upsert(item: FavoriteItem): Long {
        val now = System.currentTimeMillis()
        val existing = dao.getByTargetKey(item.targetKey)
        if (existing != null) {
            dao.update(
                item.copy(
                    id = existing.id,
                    parentId = existing.parentId,
                    createdAt = existing.createdAt,
                    updatedAt = now,
                    sortOrder = existing.sortOrder,
                ).toEntity(),
            )
            return existing.id
        }
        return dao.insert(
            item.copy(
                createdAt = now,
                updatedAt = now,
                sortOrder = now,
            ).toEntity(),
        )
    }

    override suspend fun addFolder(
        title: String,
        parentId: Long?,
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            FavoriteItem(
                parentId = parentId,
                targetType = FavoriteTargetType.FAVORITE_FOLDER,
                targetKey = "favorite-folder:${UUID.randomUUID()}",
                title = title.ifBlank { "收藏夹" },
                createdAt = now,
                updatedAt = now,
                sortOrder = now,
            ).toEntity(),
        )
    }

    override suspend fun move(
        ids: List<Long>,
        parentId: Long?,
    ) {
        val safeIds = ids.distinct().filter { it > 0L }
        if (safeIds.isEmpty()) return
        val allItems = dao.getAll()
        val blockedIds = collectDescendantIds(
            allItems = allItems,
            rootIds = safeIds,
        ) + safeIds
        if (parentId != null && parentId in blockedIds) return
        if (parentId != null) {
            val parent = allItems.firstOrNull { it.id == parentId } ?: return
            if (parent.targetType != FavoriteTargetType.FAVORITE_FOLDER.name) return
        }
        dao.move(
            ids = safeIds,
            parentId = parentId,
            updatedAt = System.currentTimeMillis(),
            sortOrder = System.currentTimeMillis(),
        )
    }

    override suspend fun updateLocalVideoTarget(
        oldLocalUri: String,
        newLocalUri: String,
        newLocalPath: String,
        newTitle: String,
        newMediaStoreId: Long,
    ) {
        val current = dao.getByTargetTypeAndLocalUri(
            targetType = FavoriteTargetType.LOCAL_VIDEO.name,
            localUri = oldLocalUri,
        ) ?: return
        updateOrDeleteDuplicate(
            current = current,
            updated = current.copy(
                targetKey = localVideoTargetKey(
                    mediaStoreId = newMediaStoreId,
                    uri = newLocalUri,
                ),
                title = newTitle,
                subtitle = newLocalPath,
                localUri = newLocalUri,
                localPath = newLocalPath,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun updateLocalFolderPath(
        oldPath: String,
        newPath: String,
    ) {
        val oldCanonicalPath = oldPath.canonicalPathOrSelf()
        val targets = dao.getByTargetType(FavoriteTargetType.LOCAL_FOLDER.name)
            .filter { entity ->
                val localPath = entity.localPath ?: entity.targetKey.removePrefix(LOCAL_FOLDER_TARGET_PREFIX)
                val canonicalPath = localPath.canonicalPathOrSelf()
                canonicalPath == oldCanonicalPath || canonicalPath.startsWith(oldCanonicalPath + File.separator)
            }
        targets.forEach { current ->
            val currentPath = current.localPath ?: current.targetKey.removePrefix(LOCAL_FOLDER_TARGET_PREFIX)
            val updatedPath = currentPath.replacePathPrefix(
                oldPath = oldPath,
                newPath = newPath,
            )
            updateOrDeleteDuplicate(
                current = current,
                updated = current.copy(
                    targetKey = LOCAL_FOLDER_TARGET_PREFIX + updatedPath.canonicalPathOrSelf(),
                    title = File(updatedPath).prettyName,
                    subtitle = updatedPath,
                    localPath = updatedPath,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun delete(ids: List<Long>) {
        val allItems = dao.getAll()
        val deleteIds = collectDescendantIds(
            allItems = allItems,
            rootIds = ids.distinct().filter { it > 0L },
        )
        if (deleteIds.isEmpty()) return
        dao.delete(deleteIds)
    }

    override suspend fun clear() {
        dao.clear()
    }

    private fun collectDescendantIds(
        allItems: List<FavoriteItemEntity>,
        rootIds: List<Long>,
    ): List<Long> {
        val childrenByParentId = allItems.groupBy { it.parentId }
        val pendingIds = ArrayDeque(rootIds)
        val result = mutableSetOf<Long>()
        while (pendingIds.isNotEmpty()) {
            val id = pendingIds.removeFirst()
            if (!result.add(id)) continue
            childrenByParentId[id].orEmpty().forEach { child -> pendingIds.add(child.id) }
        }
        return result.toList()
    }

    private suspend fun updateOrDeleteDuplicate(
        current: FavoriteItemEntity,
        updated: FavoriteItemEntity,
    ) {
        val duplicate = dao.getByTargetKey(updated.targetKey)
        if (duplicate != null && duplicate.id != current.id) {
            dao.delete(listOf(current.id))
            return
        }
        dao.update(updated)
    }

    private fun localVideoTargetKey(
        mediaStoreId: Long,
        uri: String,
    ): String {
        val targetToken = mediaStoreId.takeIf { it > 0L }?.toString() ?: uri
        return "local:video:$targetToken"
    }

    private fun String.replacePathPrefix(
        oldPath: String,
        newPath: String,
    ): String {
        val canonicalPath = canonicalPathOrSelf()
        val oldCanonicalPath = oldPath.canonicalPathOrSelf()
        val relativePath = canonicalPath.removePrefix(oldCanonicalPath).trimStart(File.separatorChar)
        if (relativePath.isBlank()) return newPath
        return File(newPath, relativePath).path
    }

    private companion object {
        const val LOCAL_FOLDER_TARGET_PREFIX = "local:folder:"
    }
}

private fun FavoriteItemEntity.toModel(): FavoriteItem = FavoriteItem(
    id = id,
    parentId = parentId,
    targetType = FavoriteTargetType.valueOf(targetType),
    targetKey = targetKey,
    title = title,
    subtitle = subtitle,
    localUri = localUri,
    localPath = localPath,
    remoteServerId = remoteServerId,
    remoteProtocol = remoteProtocol?.let(ServerProtocol::valueOf),
    remotePath = remotePath,
    remoteServerName = remoteServerName,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortOrder = sortOrder,
)

private fun FavoriteItem.toEntity(): FavoriteItemEntity = FavoriteItemEntity(
    id = id,
    parentId = parentId,
    targetType = targetType.name,
    targetKey = targetKey,
    title = title,
    subtitle = subtitle,
    localUri = localUri,
    localPath = localPath,
    remoteServerId = remoteServerId,
    remoteProtocol = remoteProtocol?.name,
    remotePath = remotePath,
    remoteServerName = remoteServerName,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortOrder = sortOrder,
)
