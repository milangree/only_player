package one.only.player.feature.player.service.subtitle

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import java.io.File
import one.only.player.core.common.extensions.getFilenameFromUri
import one.only.player.core.common.extensions.getLocalSubtitles
import one.only.player.core.common.extensions.getPath
import one.only.player.core.common.extensions.matchesSubtitleBase
import one.only.player.core.data.remote.FtpClient
import one.only.player.core.data.remote.SmbClient
import one.only.player.core.data.remote.WebDavClient
import one.only.player.core.data.repository.MediaRepository
import one.only.player.core.model.RemoteFile
import one.only.player.core.model.RemoteServer
import one.only.player.core.model.ServerProtocol
import one.only.player.feature.player.extensions.getSubtitleMime
import one.only.player.feature.player.extensions.uriToSubtitleConfiguration

internal class ExternalSubtitleLoader(
    private val context: Context,
    private val mediaRepository: MediaRepository,
    private val webDavClient: WebDavClient,
    private val smbClient: SmbClient,
    private val ftpClient: FtpClient,
) {

    fun isDirectSubtitleUri(uri: Uri): Boolean = uri.scheme in DIRECT_SUBTITLE_URI_SCHEMES

    suspend fun buildConfiguration(
        uri: Uri,
        subtitleEncoding: String,
    ): MediaItem.SubtitleConfiguration = if (isDirectSubtitleUri(uri)) {
        buildDirectSubtitleConfiguration(uri)
    } else {
        context.uriToSubtitleConfiguration(
            uri = uri,
            subtitleEncoding = subtitleEncoding,
        )
    }

    suspend fun buildConfigurations(
        mediaId: String,
        requestHeaders: Map<String, String>,
        playbackStateUri: String,
        playbackStateCandidates: List<String>,
        subtitleEncoding: String,
    ): List<MediaItem.SubtitleConfiguration> {
        val uri = mediaId.toUri()
        val video = mediaRepository.getVideoByUri(uri = playbackStateUri)
        val videoState = mediaRepository.getVideoState(
            uris = playbackStateCandidates,
        )
        val dbExternalSubs = videoState?.externalSubs ?: emptyList()
        val localSubs = (video?.path ?: context.getPath(uri))?.let {
            File(it).getLocalSubtitles(
                context = context,
                excludeSubsList = dbExternalSubs,
            )
        } ?: emptyList()
        val remoteSubs = buildRemoteSubtitleUris(
            videoUri = uri,
            requestHeaders = requestHeaders,
            excludeSubsList = dbExternalSubs,
        )

        val allExternalSubs = dbExternalSubs + localSubs + remoteSubs
        if (allExternalSubs.isEmpty()) return emptyList()

        return allExternalSubs.map { subtitleUri ->
            buildConfiguration(
                uri = subtitleUri,
                subtitleEncoding = subtitleEncoding,
            )
        }
    }

    fun mergeConfigurations(
        existing: List<MediaItem.SubtitleConfiguration>,
        incoming: List<MediaItem.SubtitleConfiguration>,
    ): List<MediaItem.SubtitleConfiguration> {
        val mergedById = LinkedHashMap<String, MediaItem.SubtitleConfiguration>()
        existing.forEach { subtitleConfiguration ->
            mergedById[subtitleConfiguration.id ?: subtitleConfiguration.uri.toString()] = subtitleConfiguration
        }
        incoming.forEach { subtitleConfiguration ->
            mergedById[subtitleConfiguration.id ?: subtitleConfiguration.uri.toString()] = subtitleConfiguration
        }
        return mergedById.values.toList()
    }

    private suspend fun buildRemoteSubtitleUris(
        videoUri: Uri,
        requestHeaders: Map<String, String>,
        excludeSubsList: List<Uri>,
    ): List<Uri> {
        val fileName = context.getFilenameFromUri(videoUri)
        val videoName = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        val excludedUris = excludeSubsList.map(Uri::toString).toSet()
        val subtitleFiles = when (videoUri.scheme) {
            "smb" -> listRemoteSmbDirectory(videoUri, requestHeaders)
            "ftp" -> listRemoteFtpDirectory(videoUri, requestHeaders)
            "http", "https" -> listRemoteWebDavDirectory(videoUri, requestHeaders)
            else -> emptyList()
        }

        return subtitleFiles
            .filter { !it.isDirectory }
            .filter { it.hasSubtitleExtension() }
            .filter { it.name.matchesSubtitleBase(videoName) }
            .map { remoteFile -> buildRemoteSubtitleUri(videoUri, remoteFile) }
            .filter { subtitleUri -> subtitleUri.toString() !in excludedUris }
    }

    private suspend fun listRemoteSmbDirectory(
        videoUri: Uri,
        requestHeaders: Map<String, String>,
    ): List<RemoteFile> {
        val host = videoUri.host ?: return emptyList()
        val shareName = videoUri.pathSegments.firstOrNull() ?: return emptyList()
        val directoryPath = "/${videoUri.pathSegments.dropLast(1).joinToString("/")}/"
        val server = RemoteServer(
            name = host,
            protocol = ServerProtocol.SMB,
            host = host,
            port = videoUri.port.takeIf { it > 0 } ?: 445,
            path = "/$shareName",
            username = requestHeaders["_smb_username"].orEmpty(),
            password = requestHeaders["_smb_password"].orEmpty(),
        )
        return smbClient.listDirectory(server, directoryPath).getOrElse { emptyList() }
    }

    private suspend fun listRemoteWebDavDirectory(
        videoUri: Uri,
        requestHeaders: Map<String, String>,
    ): List<RemoteFile> {
        val host = videoUri.host ?: return emptyList()
        val directoryPath = videoUri.path
            ?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it/" }
            ?: "/"
        val server = RemoteServer(
            name = host,
            protocol = ServerProtocol.WEBDAV,
            host = host,
            port = videoUri.port.takeIf { it > 0 },
            path = directoryPath,
            username = requestHeaders["_webdav_username"].orEmpty(),
            password = requestHeaders["_webdav_password"].orEmpty(),
        )
        return webDavClient.listDirectory(server, directoryPath).getOrElse { emptyList() }
    }

    private suspend fun listRemoteFtpDirectory(
        videoUri: Uri,
        requestHeaders: Map<String, String>,
    ): List<RemoteFile> {
        val host = videoUri.host ?: return emptyList()
        val directoryPath = videoUri.path
            ?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it/" }
            ?: "/"
        val server = RemoteServer(
            name = host,
            protocol = ServerProtocol.FTP,
            host = host,
            port = videoUri.port.takeIf { it > 0 },
            path = directoryPath,
            username = requestHeaders["_ftp_username"].orEmpty(),
            password = requestHeaders["_ftp_password"].orEmpty(),
        )
        return ftpClient.listDirectory(server, directoryPath).getOrElse { emptyList() }
    }

    private fun buildRemoteSubtitleUri(
        videoUri: Uri,
        remoteFile: RemoteFile,
    ): Uri = Uri.parse(
        "${videoUri.scheme}://${videoUri.authority}${remoteFile.path}",
    )

    private fun RemoteFile.hasSubtitleExtension(): Boolean {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isBlank()) return false
        return extension.lowercase() in REMOTE_SUBTITLE_EXTENSIONS
    }

    private fun buildDirectSubtitleConfiguration(uri: Uri): MediaItem.SubtitleConfiguration {
        val label = context.getFilenameFromUri(uri)
        return MediaItem.SubtitleConfiguration.Builder(uri).apply {
            setId(uri.toString())
            setMimeType(uri.getSubtitleMime(displayName = label))
            setLabel(label)
        }.build()
    }

    private companion object {
        private val DIRECT_SUBTITLE_URI_SCHEMES = setOf("smb", "ftp")
        private val REMOTE_SUBTITLE_EXTENSIONS = setOf(
            "ass",
            "srt",
            "ssa",
            "ttml",
            "vtt",
        )
    }
}
