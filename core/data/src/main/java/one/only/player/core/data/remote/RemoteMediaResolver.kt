package one.only.player.core.data.remote

import android.net.Uri
import java.net.URLDecoder
import javax.inject.Inject
import one.only.player.core.model.RemoteFile
import one.only.player.core.model.RemoteServer
import one.only.player.core.model.ServerProtocol

class RemoteMediaResolver @Inject constructor(
    private val webDavClient: WebDavClient,
    private val smbClient: SmbClient,
    private val ftpClient: FtpClient,
) {

    suspend fun listBrowsableFiles(
        server: RemoteServer,
        path: String,
        forceRefresh: Boolean = false,
    ): Result<List<RemoteFile>> {
        val normalizedPath = normalizeDirectoryPath(server, path)
        return when (server.protocol) {
            ServerProtocol.WEBDAV -> webDavClient.listDirectory(server, normalizedPath, forceRefresh)
            ServerProtocol.SMB -> smbClient.listDirectory(server, normalizedPath, forceRefresh)
            ServerProtocol.FTP -> ftpClient.listDirectory(server, normalizedPath, forceRefresh)
        }.map { files -> files.filterBrowsableFiles() }
    }

    fun buildPlayUrl(
        server: RemoteServer,
        file: RemoteFile,
    ): String = when (server.protocol) {
        ServerProtocol.WEBDAV -> webDavClient.buildFileUrl(server, file.path)
        ServerProtocol.SMB -> buildSmbFileUrl(server, file)
        ServerProtocol.FTP -> ftpClient.buildFileUrl(server, file.path)
    }

    fun buildVideoPlaylist(
        server: RemoteServer,
        files: List<RemoteFile>,
    ): List<Uri> = files
        .filter { file -> !file.isDirectory && file.hasBrowsableVideoExtension() }
        .map { file -> Uri.parse(buildPlayUrl(server, file)) }

    fun buildVideoPlaylistRemotePaths(files: List<RemoteFile>): List<String> = files
        .filter { file -> !file.isDirectory && file.hasBrowsableVideoExtension() }
        .map(RemoteFile::path)

    fun buildAuthHeaders(
        server: RemoteServer,
        file: RemoteFile,
    ): Map<String, String> = when (server.protocol) {
        ServerProtocol.WEBDAV -> buildMap {
            putAll(webDavClient.buildAuthHeaders(server))
            putRemoteMetadata(server, file, "webdav")
            if (server.username.isNotBlank()) {
                put("_webdav_username", server.username)
                put("_webdav_password", server.password)
            }
        }

        ServerProtocol.SMB -> buildMap {
            putRemoteMetadata(server, file, "smb")
            if (server.username.isNotBlank()) {
                put("_smb_username", server.username)
                put("_smb_password", server.password)
            }
        }

        ServerProtocol.FTP -> buildMap {
            putRemoteMetadata(server, file, "ftp")
            if (server.username.isNotBlank()) {
                put("_ftp_username", server.username)
                put("_ftp_password", server.password)
            }
        }
    }

    fun buildDocumentId(
        server: RemoteServer,
        path: String,
    ): String = "${server.id}|${Uri.encode(path)}"

    fun buildCurrentDirectoryDocumentId(
        server: RemoteServer,
        currentPath: String,
    ): String {
        val documentPath = if (isAtServerRoot(currentPath, server)) {
            "/"
        } else {
            currentPath
        }
        return buildDocumentId(server, documentPath)
    }

    fun normalizeDirectoryPath(
        server: RemoteServer,
        path: String,
    ): String = when (server.protocol) {
        ServerProtocol.SMB -> SmbClient.normalizeRemotePath(path, isDirectory = true)
        ServerProtocol.WEBDAV,
        ServerProtocol.FTP,
        -> path.ensureLeadingSlash().ensureTrailingSlash()
    }

    fun normalizeFilePath(
        server: RemoteServer,
        path: String,
    ): String = when (server.protocol) {
        ServerProtocol.SMB -> SmbClient.normalizeRemotePath(path, isDirectory = false)
        ServerProtocol.WEBDAV,
        ServerProtocol.FTP,
        -> path.ensureLeadingSlash()
    }

    fun isAtServerRoot(
        currentPath: String,
        server: RemoteServer,
    ): Boolean {
        if (server.protocol == ServerProtocol.SMB) {
            val current = SmbClient.normalizeRemotePath(currentPath, isDirectory = true).removeSuffix("/")
            val root = SmbClient.normalizeRemotePath(server.path, isDirectory = true).removeSuffix("/")
            return current.equals(root, ignoreCase = true)
        }

        val decodedCurrent = URLDecoder.decode(currentPath.removeSuffix("/"), "UTF-8")
        val decodedRoot = URLDecoder.decode(normalizeDirectoryPath(server, server.path).removeSuffix("/"), "UTF-8")
        return decodedCurrent == decodedRoot
    }

    fun protocolKey(protocol: ServerProtocol): String = when (protocol) {
        ServerProtocol.WEBDAV -> "webdav"
        ServerProtocol.SMB -> "smb"
        ServerProtocol.FTP -> "ftp"
    }

    private fun MutableMap<String, String>.putRemoteMetadata(
        server: RemoteServer,
        file: RemoteFile,
        protocol: String,
    ) {
        put("_remote_server_id", server.id.toString())
        put("_remote_file_path", file.path)
        put("_remote_protocol", protocol)
    }

    private fun List<RemoteFile>.filterBrowsableFiles(): List<RemoteFile> = filter { file ->
        file.isDirectory || file.hasBrowsableVideoExtension()
    }

    private fun RemoteFile.hasBrowsableVideoExtension(): Boolean {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isBlank()) return false
        return extension.lowercase() in BROWSABLE_VIDEO_EXTENSIONS
    }

    private fun String.ensureLeadingSlash(): String = if (startsWith('/')) this else "/$this"

    private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"

    private fun buildSmbFileUrl(
        server: RemoteServer,
        file: RemoteFile,
    ): String {
        val sharePath = SmbClient.resolveSharePath(
            serverPath = server.path,
            path = file.path,
        )
        val relativePath = sharePath.relativePath.replace('\\', '/').trim('/')
        val uriPath = buildList {
            add(sharePath.shareName)
            if (relativePath.isNotBlank()) add(relativePath)
        }.joinToString(separator = "/")
        return Uri.Builder()
            .scheme("smb")
            .encodedAuthority("${server.host}:${server.port ?: DEFAULT_SMB_PORT}")
            .path(uriPath)
            .build()
            .toString()
    }

    companion object {
        private const val DEFAULT_SMB_PORT = 445
        val BROWSABLE_VIDEO_EXTENSIONS = setOf(
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
