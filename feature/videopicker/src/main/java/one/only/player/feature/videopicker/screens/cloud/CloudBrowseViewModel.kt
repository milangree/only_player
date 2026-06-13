package one.only.player.feature.videopicker.screens.cloud

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URLDecoder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.only.player.core.common.Dispatcher
import one.only.player.core.common.NextDispatchers
import one.only.player.core.common.Utils
import one.only.player.core.data.models.RemotePlaybackInfo
import one.only.player.core.data.remote.FtpClient
import one.only.player.core.data.remote.SmbClient
import one.only.player.core.data.remote.WebDavClient
import one.only.player.core.data.repository.MediaRepository
import one.only.player.core.data.repository.PreferencesRepository
import one.only.player.core.data.repository.RemoteServerRepository
import one.only.player.core.data.repository.buildRemoteFolderPlaybackAnchorKey
import one.only.player.core.data.repository.buildRemotePlaybackStateKey
import one.only.player.core.media.info.RemoteMediaInfo
import one.only.player.core.media.info.RemoteMediaInfoReader
import one.only.player.core.model.ApplicationPreferences
import one.only.player.core.model.RemoteFile
import one.only.player.core.model.RemoteServer
import one.only.player.core.model.ServerProtocol
import one.only.player.core.model.Video

@HiltViewModel
class CloudBrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RemoteServerRepository,
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val webDavClient: WebDavClient,
    private val smbClient: SmbClient,
    private val ftpClient: FtpClient,
    private val remoteMediaInfoReader: RemoteMediaInfoReader,
    @Dispatcher(NextDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val serverId: Long = savedStateHandle["serverId"] ?: 0L
    private val initialPath: String = (savedStateHandle["initialPath"] as? String)?.let(Uri::decode) ?: "/"

    private val _uiState = MutableStateFlow(CloudBrowseUiState())
    val uiState = _uiState.asStateFlow()
    private var fileInfoJob: Job? = null
    private var fileInfoRequestId = 0L

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                _uiState.update { it.copy(preferences = preferences) }
            }
        }
        loadServer()
    }

    fun onEvent(event: CloudBrowseEvent) {
        when (event) {
            is CloudBrowseEvent.LoadFileInfo -> loadFileInfo(event.file, event.documentUri)
            CloudBrowseEvent.DismissFileInfo -> dismissFileInfo()
            CloudBrowseEvent.Retry -> loadCurrentDirectory(forceRefresh = true)
            CloudBrowseEvent.RefreshPlaybackStates -> loadPlaybackStates()
        }
    }

    private fun loadServer() {
        viewModelScope.launch {
            val server = repository.getById(serverId)
            if (server == null) {
                _uiState.update { it.copy(isError = true, errorMessage = "Server not found") }
                return@launch
            }
            val serverRootPath = normalizeDirectoryPath(server, server.path)
            val initialDirectoryPath = normalizeDirectoryPath(server, initialPath)
            val startPath = initialDirectoryPath.takeIf { it != "/" || serverRootPath == "/" } ?: serverRootPath
            _uiState.update {
                it.copy(
                    server = server,
                    currentPath = startPath,
                    isAtRoot = isAtServerRoot(startPath, server),
                )
            }
            loadCurrentDirectory()
        }
    }

    private fun loadCurrentDirectory(forceRefresh: Boolean = false) {
        val currentState = _uiState.value
        val server = currentState.server ?: return
        val path = currentState.currentPath

        if (currentState.isLoading) return
        if (!forceRefresh && currentState.hasLoadedDirectory) return

        _uiState.update {
            it.copy(
                isLoading = true,
                isRefreshing = forceRefresh && currentState.hasLoadedDirectory,
                isError = false,
            )
        }

        viewModelScope.launch(ioDispatcher) {
            when (server.protocol) {
                ServerProtocol.WEBDAV -> loadWebDavDirectory(server, path, forceRefresh)
                ServerProtocol.SMB -> loadSmbDirectory(server, path, forceRefresh)
                ServerProtocol.FTP -> loadFtpDirectory(server, path, forceRefresh)
            }
        }
    }

    private suspend fun loadWebDavDirectory(
        server: RemoteServer,
        path: String,
        forceRefresh: Boolean,
    ) {
        webDavClient.listDirectory(server, path, forceRefresh = forceRefresh)
            .onSuccess { files ->
                val browsableFiles = files.filterBrowsableFiles()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        files = browsableFiles,
                        isError = false,
                        errorMessage = "",
                        playbackStates = emptyMap(),
                        restoreTargetFilePath = null,
                        hasLoadedDirectory = true,
                    )
                }
                loadPlaybackStates(
                    server = server,
                    directoryPath = path,
                    files = browsableFiles,
                )
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isError = true,
                        errorMessage = error.message ?: "Unknown error",
                    )
                }
            }
    }

    private suspend fun loadSmbDirectory(
        server: RemoteServer,
        path: String,
        forceRefresh: Boolean,
    ) {
        smbClient.listDirectory(server, path, forceRefresh = forceRefresh)
            .onSuccess { files ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        files = files.filterBrowsableFiles(),
                        isError = false,
                        errorMessage = "",
                        playbackStates = emptyMap(),
                        restoreTargetFilePath = null,
                        hasLoadedDirectory = true,
                    )
                }
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isError = true,
                        errorMessage = error.message ?: "Unknown error",
                    )
                }
            }
    }

    private suspend fun loadFtpDirectory(
        server: RemoteServer,
        path: String,
        forceRefresh: Boolean,
    ) {
        ftpClient.listDirectory(server, path, forceRefresh = forceRefresh)
            .onSuccess { files ->
                val browsableFiles = files.filterBrowsableFiles()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        files = browsableFiles,
                        isError = false,
                        errorMessage = "",
                        playbackStates = emptyMap(),
                        restoreTargetFilePath = null,
                        hasLoadedDirectory = true,
                    )
                }
                loadPlaybackStates(
                    server = server,
                    directoryPath = path,
                    files = browsableFiles,
                )
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isError = true,
                        errorMessage = error.message ?: "Unknown error",
                    )
                }
            }
    }

    private fun loadPlaybackStates(
        server: RemoteServer? = _uiState.value.server,
        directoryPath: String = _uiState.value.currentPath,
        files: List<RemoteFile> = _uiState.value.files,
    ) {
        val currentServer = server ?: return
        val videoFiles = files.filter { !it.isDirectory }
        if (videoFiles.isEmpty()) {
            _uiState.update {
                it.copy(
                    playbackStates = emptyMap(),
                    restoreTargetFilePath = null,
                )
            }
            return
        }

        val protocol = when (currentServer.protocol) {
            ServerProtocol.WEBDAV -> "webdav"
            ServerProtocol.SMB -> return
            ServerProtocol.FTP -> "ftp"
        }

        val pathToKey = videoFiles.mapNotNull { file ->
            val key = buildRemotePlaybackStateKey(
                remoteProtocol = protocol,
                remoteServerId = currentServer.id,
                remoteFilePath = file.path,
            ) ?: return@mapNotNull null
            file.path to key
        }

        viewModelScope.launch(ioDispatcher) {
            val stateKeys = pathToKey.map { it.second }
            val states = mediaRepository.getRemotePlaybackStates(stateKeys)
            val keyToPath = pathToKey.associate { (path, key) -> key to path }
            val playbackStates = states.entries.associate { (key, info) ->
                (keyToPath[key] ?: key) to info
            }
            val restoreTargetFilePath = buildRemoteFolderPlaybackAnchorKey(
                remoteProtocol = protocol,
                remoteServerId = currentServer.id,
                directoryPath = directoryPath,
            )?.let { anchorKey ->
                preferencesRepository.applicationPreferences.value.remoteFolderLastPlayedMediaPaths[anchorKey]
            }
            _uiState.update { currentState ->
                if (currentState.currentPath != directoryPath) {
                    return@update currentState
                }
                currentState.copy(
                    playbackStates = playbackStates,
                    restoreTargetFilePath = restoreTargetFilePath,
                )
            }
        }
    }

    fun buildPlayUrl(file: RemoteFile): String? {
        val server = _uiState.value.server ?: return null
        return when (server.protocol) {
            ServerProtocol.WEBDAV -> webDavClient.buildFileUrl(server, file.path)
            ServerProtocol.SMB -> {
                val port = server.port ?: 445
                "smb://${server.host}:$port${file.path}"
            }
            ServerProtocol.FTP -> ftpClient.buildFileUrl(server, file.path)
        }
    }

    fun buildAllVideoPlayUrls(): List<Uri> {
        val server = _uiState.value.server ?: return emptyList()
        return _uiState.value.files
            .filter { !it.isDirectory }
            .mapNotNull { file ->
                when (server.protocol) {
                    ServerProtocol.WEBDAV -> webDavClient.buildFileUrl(server, file.path)
                    ServerProtocol.SMB -> {
                        val port = server.port ?: 445
                        "smb://${server.host}:$port${file.path}"
                    }
                    ServerProtocol.FTP -> ftpClient.buildFileUrl(server, file.path)
                }
            }
            .map { Uri.parse(it) }
    }

    fun buildCurrentDirectoryDocumentId(): String? {
        val server = _uiState.value.server ?: return null
        val documentPath = if (isAtServerRoot(_uiState.value.currentPath, server)) {
            "/"
        } else {
            _uiState.value.currentPath
        }
        return "${server.id}|${Uri.encode(documentPath)}"
    }

    fun buildFileDocumentId(file: RemoteFile): String? {
        val server = _uiState.value.server ?: return null
        return "${server.id}|${Uri.encode(file.path)}"
    }

    fun buildAuthHeaders(file: RemoteFile): Map<String, String> {
        val server = _uiState.value.server ?: return emptyMap()
        return when (server.protocol) {
            ServerProtocol.WEBDAV -> buildMap {
                putAll(webDavClient.buildAuthHeaders(server))
                put("_remote_server_id", server.id.toString())
                put("_remote_file_path", file.path)
                put("_remote_protocol", "webdav")
                if (server.username.isNotBlank()) {
                    put("_webdav_username", server.username)
                    put("_webdav_password", server.password)
                }
            }

            ServerProtocol.SMB -> buildMap {
                if (server.username.isNotBlank()) {
                    put("_smb_username", server.username)
                    put("_smb_password", server.password)
                }
            }

            ServerProtocol.FTP -> buildMap {
                put("_remote_server_id", server.id.toString())
                put("_remote_file_path", file.path)
                put("_remote_protocol", "ftp")
                if (server.username.isNotBlank()) {
                    put("_ftp_username", server.username)
                    put("_ftp_password", server.password)
                }
            }
        }
    }

    private fun List<RemoteFile>.filterBrowsableFiles(): List<RemoteFile> = filter { file ->
        file.isDirectory || file.hasBrowsableVideoExtension()
    }

    private fun RemoteFile.hasBrowsableVideoExtension(): Boolean {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isBlank()) return false
        return extension.lowercase() in BROWSABLE_VIDEO_EXTENSIONS
    }

    private fun loadFileInfo(
        file: RemoteFile,
        documentUri: Uri,
    ) {
        val server = _uiState.value.server ?: return
        if (file.isDirectory) return
        if (_uiState.value.isLoadingFileInfo) return

        val requestId = ++fileInfoRequestId
        _uiState.update {
            it.copy(
                isLoadingFileInfo = true,
                infoVideo = null,
            )
        }

        fileInfoJob = viewModelScope.launch {
            val probeUrl = buildProbeUrl(server, file)
            val mediaInfo = remoteMediaInfoReader.read(
                probeUrl = probeUrl,
                documentUri = documentUri,
            ).getOrNull()
            val video = file.toVideo(
                documentUri = documentUri,
                playUrl = buildPlayUrl(file),
                mediaInfo = mediaInfo,
            )
            _uiState.update { currentState ->
                if (requestId != fileInfoRequestId) {
                    return@update currentState
                }
                fileInfoJob = null
                currentState.copy(
                    isLoadingFileInfo = false,
                    infoVideo = video,
                )
            }
        }
    }

    private fun dismissFileInfo() {
        fileInfoRequestId++
        fileInfoJob?.cancel()
        fileInfoJob = null
        _uiState.update {
            it.copy(
                isLoadingFileInfo = false,
                infoVideo = null,
            )
        }
    }

    private fun buildProbeUrl(
        server: RemoteServer,
        file: RemoteFile,
    ): String? {
        val playUrl = when (server.protocol) {
            ServerProtocol.WEBDAV -> webDavClient.buildFileUrl(server, file.path)
            ServerProtocol.FTP -> ftpClient.buildFileUrl(server, file.path)
            ServerProtocol.SMB -> return null
        }
        if (server.username.isBlank()) return playUrl

        val uri = Uri.parse(playUrl)
        val authority = uri.encodedAuthority ?: return playUrl
        val userInfo = "${Uri.encode(server.username)}:${Uri.encode(server.password)}"
        return uri.buildUpon()
            .encodedAuthority("$userInfo@$authority")
            .build()
            .toString()
    }

    private fun RemoteFile.toVideo(
        documentUri: Uri,
        playUrl: String?,
        mediaInfo: RemoteMediaInfo?,
    ): Video {
        val videoStream = mediaInfo?.videoStream
        val duration = mediaInfo?.duration?.takeIf { it > 0 } ?: 0L
        return Video(
            id = path.hashCode().toLong(),
            path = path,
            parentPath = parentPath(),
            duration = duration,
            uriString = playUrl ?: documentUri.toString(),
            nameWithExtension = name,
            width = videoStream?.frameWidth ?: 0,
            height = videoStream?.frameHeight ?: 0,
            size = size,
            formattedDuration = duration.takeIf { it > 0 }?.let(Utils::formatDurationMillis).orEmpty(),
            formattedFileSize = Utils.formatFileSize(size),
            format = mediaInfo?.format ?: contentType.takeIf { it.isNotBlank() },
            videoStream = videoStream,
            audioStreams = mediaInfo?.audioStreams.orEmpty(),
            subtitleStreams = mediaInfo?.subtitleStreams.orEmpty(),
        )
    }

    private fun RemoteFile.parentPath(): String {
        val parentPath = path.trimEnd('/').substringBeforeLast("/", missingDelimiterValue = "")
        return parentPath.ifBlank { "/" }
    }

    private fun normalizeDirectoryPath(
        server: RemoteServer,
        path: String,
    ): String = when (server.protocol) {
        ServerProtocol.SMB -> SmbClient.normalizeRemotePath(path, isDirectory = true)
        ServerProtocol.WEBDAV,
        ServerProtocol.FTP,
        -> path.ensureLeadingSlash().ensureTrailingSlash()
    }

    private fun isAtServerRoot(currentPath: String, server: RemoteServer): Boolean {
        if (server.protocol == ServerProtocol.SMB) {
            val current = SmbClient.normalizeRemotePath(currentPath, isDirectory = true).removeSuffix("/")
            val root = SmbClient.normalizeRemotePath(server.path, isDirectory = true).removeSuffix("/")
            return current.equals(root, ignoreCase = true)
        }

        val decodedCurrent = URLDecoder.decode(currentPath.removeSuffix("/"), "UTF-8")
        val decodedRoot = URLDecoder.decode(normalizeDirectoryPath(server, server.path).removeSuffix("/"), "UTF-8")
        return decodedCurrent == decodedRoot
    }

    private fun String.ensureLeadingSlash(): String = if (startsWith('/')) this else "/$this"

    private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"

    companion object {
        private val BROWSABLE_VIDEO_EXTENSIONS = setOf(
            "3gp",
            "avi",
            "flv",
            "m2ts",
            "m4v",
            "mkv",
            "mov",
            "mp4",
            "mts",
            "ts",
            "webm",
            "wmv",
        )
    }
}

@Stable
data class CloudBrowseUiState(
    val server: RemoteServer? = null,
    val currentPath: String = "/",
    val files: List<RemoteFile> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoadedDirectory: Boolean = false,
    val isError: Boolean = false,
    val isAtRoot: Boolean = true,
    val errorMessage: String = "",
    val playbackStates: Map<String, RemotePlaybackInfo> = emptyMap(),
    val restoreTargetFilePath: String? = null,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val isLoadingFileInfo: Boolean = false,
    val infoVideo: Video? = null,
)

sealed interface CloudBrowseEvent {
    data class LoadFileInfo(
        val file: RemoteFile,
        val documentUri: Uri,
    ) : CloudBrowseEvent

    data object DismissFileInfo : CloudBrowseEvent
    data object Retry : CloudBrowseEvent
    data object RefreshPlaybackStates : CloudBrowseEvent
}
