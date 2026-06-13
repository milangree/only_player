package one.only.player.core.data.remote

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.only.player.core.common.di.ApplicationScope
import one.only.player.core.model.RemoteFile
import one.only.player.core.model.RemoteServer
import one.only.player.core.model.ServerProtocol
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile

@Singleton
class FtpClient @Inject constructor(
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    private val browseMutex = Mutex()
    private var browseSession: BrowseSession? = null
    private var closeBrowseSessionJob: Job? = null
    private val directoryCache = LinkedHashMap<DirectoryCacheKey, DirectoryCacheEntry>()

    suspend fun listDirectory(
        server: RemoteServer,
        directoryPath: String,
        forceRefresh: Boolean = false,
    ): Result<List<RemoteFile>> = try {
        Result.success(
            listDirectoryInternal(
                server = server,
                directoryPath = directoryPath,
                forceRefresh = forceRefresh,
            ),
        )
    } catch (exception: CancellationException) {
        throw exception
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

    private suspend fun listDirectoryInternal(
        server: RemoteServer,
        directoryPath: String,
        forceRefresh: Boolean,
    ): List<RemoteFile> {
        if (server.protocol != ServerProtocol.FTP) {
            error("FtpClient only supports FTP protocol")
        }

        val normalizedPath = directoryPath.toFtpPath()
        val cacheKey = DirectoryCacheKey(
            host = server.host,
            port = server.port ?: DEFAULT_PORT,
            authFingerprint = server.toAuthFingerprint(),
            directoryPath = normalizedPath,
        )

        return browseMutex.withLock {
            if (forceRefresh) {
                directoryCache.remove(cacheKey)
            }

            val cachedFiles = directoryCache[cacheKey]
                ?.takeIf { !forceRefresh && it.isFresh() }
                ?.files

            if (cachedFiles != null) {
                cachedFiles
            } else {
                val files = listDirectoryWithRetry(server, normalizedPath)
                cacheDirectory(cacheKey, files)
                files
            }
        }
    }

    private fun listDirectoryWithRetry(
        server: RemoteServer,
        directoryPath: String,
    ): List<RemoteFile> = try {
        listDirectoryOnce(server, directoryPath)
    } catch (exception: Exception) {
        closeBrowseSession()
        listDirectoryOnce(server, directoryPath)
    }

    private fun listDirectoryOnce(
        server: RemoteServer,
        directoryPath: String,
    ): List<RemoteFile> {
        val client = getBrowseClient(server)
        return client.listFiles(directoryPath)
            .filter { file -> file.name != "." && file.name != ".." }
            .map { file -> file.toRemoteFile(directoryPath) }
            .sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name })
    }

    private fun getBrowseClient(server: RemoteServer): FTPClient {
        val sessionKey = BrowseSessionKey(
            host = server.host,
            port = server.port ?: DEFAULT_PORT,
            authFingerprint = server.toAuthFingerprint(),
        )
        browseSession?.takeIf { it.key == sessionKey && it.isFresh() && it.client.isConnected }?.let { session ->
            session.touch()
            scheduleBrowseSessionClose(session)
            return session.client
        }
        closeBrowseSession()

        val client = server.connect(
            connectTimeoutMs = BROWSE_CONNECT_TIMEOUT_MS,
            dataTimeoutMs = BROWSE_DATA_TIMEOUT_MS,
        )
        browseSession = BrowseSession(
            key = sessionKey,
            client = client,
            lastUsedAtMillis = System.currentTimeMillis(),
        ).also(::scheduleBrowseSessionClose)
        return client
    }

    private fun closeBrowseSession() {
        closeBrowseSessionJob?.cancel()
        closeBrowseSessionJob = null
        closeBrowseSessionNow()
    }

    private fun closeBrowseSessionNow() {
        val session = browseSession ?: return
        session.client.disconnectQuietly()
        browseSession = null
    }

    private fun scheduleBrowseSessionClose(session: BrowseSession) {
        closeBrowseSessionJob?.cancel()
        closeBrowseSessionJob = applicationScope.launch {
            delay(BROWSE_SESSION_IDLE_TTL_MS)
            browseMutex.withLock {
                if (browseSession === session && !session.isFresh()) {
                    closeBrowseSessionJob = null
                    closeBrowseSessionNow()
                }
            }
        }
    }

    private fun cacheDirectory(
        key: DirectoryCacheKey,
        files: List<RemoteFile>,
    ) {
        directoryCache.remove(key)
        directoryCache[key] = DirectoryCacheEntry(
            files = files,
            createdAtMillis = System.currentTimeMillis(),
        )

        while (directoryCache.size > MAX_DIRECTORY_CACHE_SIZE) {
            val oldestKey = directoryCache.keys.firstOrNull() ?: return
            directoryCache.remove(oldestKey)
        }
    }

    fun buildFileUrl(server: RemoteServer, filePath: String): String {
        val authority = server.port?.let { "${server.host}:$it" } ?: server.host
        return Uri.Builder()
            .scheme("ftp")
            .encodedAuthority(authority)
            .path(filePath.ensureLeadingSlash())
            .build()
            .toString()
    }

    private data class BrowseSessionKey(
        val host: String,
        val port: Int,
        val authFingerprint: String,
    )

    private data class BrowseSession(
        val key: BrowseSessionKey,
        val client: FTPClient,
        var lastUsedAtMillis: Long,
    ) {
        fun isFresh(): Boolean = System.currentTimeMillis() - lastUsedAtMillis <= BROWSE_SESSION_IDLE_TTL_MS

        fun touch() {
            lastUsedAtMillis = System.currentTimeMillis()
        }
    }

    private data class DirectoryCacheKey(
        val host: String,
        val port: Int,
        val authFingerprint: String,
        val directoryPath: String,
    )

    private data class DirectoryCacheEntry(
        val files: List<RemoteFile>,
        val createdAtMillis: Long,
    ) {
        fun isFresh(): Boolean = System.currentTimeMillis() - createdAtMillis <= DIRECTORY_CACHE_TTL_MS
    }

    companion object {
        const val DEFAULT_PORT = 21
        const val CONNECT_TIMEOUT_MS = 15_000
        const val DATA_TIMEOUT_MS = 30_000
        private const val BROWSE_CONNECT_TIMEOUT_MS = 6_000
        private const val BROWSE_DATA_TIMEOUT_MS = 6_000
        private const val DIRECTORY_CACHE_TTL_MS = 30_000L
        private const val BROWSE_SESSION_IDLE_TTL_MS = 60_000L
        private const val MAX_DIRECTORY_CACHE_SIZE = 32

        private fun RemoteServer.connect(
            connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
            dataTimeoutMs: Int = DATA_TIMEOUT_MS,
        ): FTPClient {
            val client = FTPClient().apply {
                connectTimeout = connectTimeoutMs
                dataTimeout = java.time.Duration.ofMillis(dataTimeoutMs.toLong())
                setControlEncoding(Charsets.UTF_8.name())
                setAutodetectUTF8(true)
            }
            try {
                client.connect(host, port ?: DEFAULT_PORT)
                val loginOk = when {
                    username.isBlank() -> client.login("anonymous", "")
                    else -> client.login(username, password)
                }
                if (!loginOk) {
                    error("FTP login failed")
                }
                client.enterLocalPassiveMode()
                client.setFileType(FTPClient.BINARY_FILE_TYPE)
                return client
            } catch (exception: Exception) {
                client.disconnectQuietly()
                throw exception
            }
        }

        fun FTPClient.disconnectQuietly() {
            runCatching {
                if (isConnected) logout()
            }
            runCatching {
                if (isConnected) disconnect()
            }
        }

        fun String.toFtpPath(): String = ensureLeadingSlash()
    }
}

private fun FTPFile.toRemoteFile(directoryPath: String): RemoteFile {
    val fullPath = directoryPath.ensureTrailingSlash() + name
    return RemoteFile(
        name = name,
        path = if (isDirectory) fullPath.ensureTrailingSlash() else fullPath,
        isDirectory = isDirectory,
        size = size,
    )
}

private fun String.ensureLeadingSlash(): String = if (startsWith('/')) this else "/$this"

private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"
