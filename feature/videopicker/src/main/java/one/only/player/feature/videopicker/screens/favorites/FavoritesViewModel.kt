package one.only.player.feature.videopicker.screens.favorites

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import one.only.player.core.common.Dispatcher
import one.only.player.core.common.NextDispatchers
import one.only.player.core.data.remote.RemoteMediaResolver
import one.only.player.core.data.repository.FavoriteRepository
import one.only.player.core.data.repository.MediaRepository
import one.only.player.core.data.repository.RemoteServerRepository
import one.only.player.core.model.FavoriteItem
import one.only.player.core.model.FavoriteTargetType
import one.only.player.core.model.RemoteFile

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val mediaRepository: MediaRepository,
    private val remoteServerRepository: RemoteServerRepository,
    private val remoteMediaResolver: RemoteMediaResolver,
    @Dispatcher(NextDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val currentParentId = MutableStateFlow<Long?>(null)
    private val openTarget = MutableStateFlow<FavoriteOpenTarget?>(null)
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        favoriteRepository.observeAll(),
        searchQuery,
        currentParentId,
        openTarget,
        message,
    ) { allItems, query, parentId, target, message ->
        val normalizedQuery = query.trim()
        val visibleItems = if (normalizedQuery.isBlank()) {
            allItems.filter { item -> item.parentId == parentId }
        } else {
            allItems.filter { item -> item.matches(normalizedQuery) }
        }
        FavoritesUiState(
            allItems = allItems,
            visibleItems = visibleItems,
            searchQuery = query,
            currentParentId = parentId,
            currentTitle = allItems.firstOrNull { it.id == parentId }?.title,
            openTarget = target,
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FavoritesUiState(),
    )

    fun onEvent(event: FavoritesUiEvent) {
        when (event) {
            is FavoritesUiEvent.UpdateSearchQuery -> searchQuery.value = event.value
            is FavoritesUiEvent.OpenItem -> openItem(event.item)
            is FavoritesUiEvent.OpenFolder -> currentParentId.value = event.id
            FavoritesUiEvent.NavigateParent -> navigateParent()
            is FavoritesUiEvent.AddFolder -> addFolder(event.title)
            is FavoritesUiEvent.Delete -> delete(event.item)
            is FavoritesUiEvent.Move -> move(event.item, event.parentId)
            FavoritesUiEvent.ConsumeOpenTarget -> openTarget.value = null
            FavoritesUiEvent.ConsumeMessage -> message.value = null
        }
    }

    private fun openItem(item: FavoriteItem) {
        when (item.targetType) {
            FavoriteTargetType.FAVORITE_FOLDER -> {
                currentParentId.value = item.id
            }
            FavoriteTargetType.LOCAL_VIDEO -> {
                openLocalVideo(item)
            }
            FavoriteTargetType.LOCAL_FOLDER -> {
                val path = item.localPath
                if (path.isNullOrBlank()) {
                    message.value = "收藏记录缺少本地文件夹路径"
                    return
                }
                openTarget.value = FavoriteOpenTarget.LocalFolder(path)
            }
            FavoriteTargetType.REMOTE_SERVER_ROOT,
            FavoriteTargetType.REMOTE_DIRECTORY,
            FavoriteTargetType.REMOTE_FILE,
            -> openRemoteItem(item)
        }
    }

    private fun openLocalVideo(item: FavoriteItem) {
        viewModelScope.launch {
            val video = resolveLocalVideo(item)
            val uri = video?.uriString?.toUri()
                ?: item.localUri?.toUri()
                ?: item.localPath?.let { Uri.fromFile(File(it)) }
            if (uri == null) {
                message.value = "收藏记录缺少本地视频路径"
                return@launch
            }
            openTarget.value = FavoriteOpenTarget.LocalVideo(uri)
        }
    }

    private suspend fun resolveLocalVideo(item: FavoriteItem) = item.localUri
        ?.let { mediaRepository.getVideoByUri(it) }
        ?: mediaRepository.getVideosFlow().first().firstOrNull { video ->
            val targetToken = item.targetKey.removePrefix("local:video:")
            video.uriString == targetToken ||
                video.id.toString() == targetToken ||
                video.path == item.localPath
        }

    private fun openRemoteItem(item: FavoriteItem) {
        viewModelScope.launch(ioDispatcher) {
            val serverId = item.remoteServerId ?: run {
                message.value = "收藏记录缺少云端服务器"
                return@launch
            }
            val server = remoteServerRepository.getById(serverId) ?: run {
                message.value = "云端服务器已删除"
                return@launch
            }
            when (item.targetType) {
                FavoriteTargetType.REMOTE_SERVER_ROOT -> {
                    openTarget.value = FavoriteOpenTarget.RemoteDirectory(
                        serverId = server.id,
                        path = remoteMediaResolver.normalizeDirectoryPath(server, server.path),
                    )
                }
                FavoriteTargetType.REMOTE_DIRECTORY -> {
                    val path = item.remotePath ?: "/"
                    openTarget.value = FavoriteOpenTarget.RemoteDirectory(
                        serverId = server.id,
                        path = remoteMediaResolver.normalizeDirectoryPath(server, path),
                    )
                }
                FavoriteTargetType.REMOTE_FILE -> {
                    val path = item.remotePath ?: run {
                        message.value = "收藏记录缺少云端文件路径"
                        return@launch
                    }
                    val file = RemoteFile(
                        name = item.title,
                        path = remoteMediaResolver.normalizeFilePath(server, path),
                        isDirectory = false,
                    )
                    val parentPath = file.path.trimEnd('/').substringBeforeLast("/", missingDelimiterValue = "").ifBlank { "/" }
                    val files = remoteMediaResolver.listBrowsableFiles(server, parentPath).getOrNull()
                    val playlist = files
                        ?.let { remoteFiles -> remoteMediaResolver.buildVideoPlaylist(server, remoteFiles) }
                        ?: listOf(Uri.parse(remoteMediaResolver.buildPlayUrl(server, file)))
                    val playlistRemotePaths = files
                        ?.let(remoteMediaResolver::buildVideoPlaylistRemotePaths)
                        ?: listOf(file.path)
                    openTarget.value = FavoriteOpenTarget.RemoteVideo(
                        uri = Uri.parse(remoteMediaResolver.buildPlayUrl(server, file)),
                        headers = remoteMediaResolver.buildAuthHeaders(server, file),
                        initialSubtitleDocumentId = remoteMediaResolver.buildDocumentId(server, parentPath),
                        playlist = playlist,
                        playlistRemotePaths = playlistRemotePaths,
                    )
                }
                else -> Unit
            }
        }
    }

    private fun navigateParent() {
        val parentId = currentParentId.value ?: return
        val parent = uiState.value.allItems.firstOrNull { it.id == parentId }
        currentParentId.value = parent?.parentId
    }

    private fun addFolder(title: String) {
        viewModelScope.launch {
            favoriteRepository.addFolder(
                title = title,
                parentId = currentParentId.value,
            )
        }
    }

    private fun delete(item: FavoriteItem) {
        viewModelScope.launch {
            favoriteRepository.delete(listOf(item.id))
        }
    }

    private fun move(
        item: FavoriteItem,
        parentId: Long?,
    ) {
        viewModelScope.launch {
            favoriteRepository.move(
                ids = listOf(item.id),
                parentId = parentId,
            )
        }
    }

    private fun FavoriteItem.matches(query: String): Boolean = title.contains(query, ignoreCase = true) ||
        subtitle.contains(query, ignoreCase = true) ||
        localPath.orEmpty().contains(query, ignoreCase = true) ||
        remotePath.orEmpty().contains(query, ignoreCase = true) ||
        remoteServerName.orEmpty().contains(query, ignoreCase = true)
}

@Stable
data class FavoritesUiState(
    val allItems: List<FavoriteItem> = emptyList(),
    val visibleItems: List<FavoriteItem> = emptyList(),
    val searchQuery: String = "",
    val currentParentId: Long? = null,
    val currentTitle: String? = null,
    val openTarget: FavoriteOpenTarget? = null,
    val message: String? = null,
)

sealed interface FavoritesUiEvent {
    data class UpdateSearchQuery(val value: String) : FavoritesUiEvent
    data class OpenItem(val item: FavoriteItem) : FavoritesUiEvent
    data class OpenFolder(val id: Long) : FavoritesUiEvent
    data object NavigateParent : FavoritesUiEvent
    data class AddFolder(val title: String) : FavoritesUiEvent
    data class Delete(val item: FavoriteItem) : FavoritesUiEvent
    data class Move(
        val item: FavoriteItem,
        val parentId: Long?,
    ) : FavoritesUiEvent
    data object ConsumeOpenTarget : FavoritesUiEvent
    data object ConsumeMessage : FavoritesUiEvent
}

sealed interface FavoriteOpenTarget {
    data class LocalVideo(val uri: Uri) : FavoriteOpenTarget
    data class LocalFolder(val path: String) : FavoriteOpenTarget
    data class RemoteDirectory(
        val serverId: Long,
        val path: String,
    ) : FavoriteOpenTarget
    data class RemoteVideo(
        val uri: Uri,
        val headers: Map<String, String>,
        val initialSubtitleDocumentId: String?,
        val playlist: List<Uri>,
        val playlistRemotePaths: List<String>,
    ) : FavoriteOpenTarget
}
