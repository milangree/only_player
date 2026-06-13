package one.only.player.core.data.remote

import android.net.Uri
import android.util.Base64
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import one.only.player.core.model.RemoteFile
import one.only.player.core.model.RemoteServer
import one.only.player.core.model.ServerProtocol
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

@Singleton
class WebDavClient @Inject constructor() {

    private val browseMutex = Mutex()
    private val directoryCache = LinkedHashMap<DirectoryCacheKey, DirectoryCacheEntry>()
    private val httpClients = LinkedHashMap<HttpClientKey, OkHttpClient>()

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
        if (server.protocol != ServerProtocol.WEBDAV) {
            error("WebDavClient only supports WEBDAV protocol")
        }

        val baseUrl = buildBaseUrl(server)
        val normalizedPath = directoryPath.ensureTrailingSlash()
        val cacheKey = DirectoryCacheKey(
            baseUrl = baseUrl,
            authFingerprint = server.toAuthFingerprint(),
            isProxyEnabled = server.isProxyEnabled,
            proxyHost = server.proxyHost,
            proxyPort = server.proxyPort,
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
                val files = requestDirectory(
                    server = server,
                    baseUrl = baseUrl,
                    normalizedPath = normalizedPath,
                )
                cacheDirectory(cacheKey, files)
                files
            }
        }
    }

    private fun requestDirectory(
        server: RemoteServer,
        baseUrl: String,
        normalizedPath: String,
    ): List<RemoteFile> {
        val url = "$baseUrl$normalizedPath"

        val client = getHttpClient(server)
        val requestBody = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .method("PROPFIND", requestBody)
            .header("Depth", "1")
        applyAuth(requestBuilder, server)

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 207) {
                error("WebDAV PROPFIND failed: HTTP ${response.code}")
            }

            return parsePropfindResponse(
                inputStream = response.body.byteStream(),
                requestPath = normalizedPath,
                baseUrl = baseUrl,
            )
        }
    }

    fun buildFileUrl(server: RemoteServer, filePath: String): String {
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) return filePath

        val baseUrl = buildBaseUrl(server)
        return "$baseUrl${filePath.ensureLeadingSlash()}"
    }

    fun buildAuthHeaders(server: RemoteServer): Map<String, String> {
        if (server.username.isBlank()) return emptyMap()

        val credentials = "${server.username}:${server.password}"
        val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        return mapOf("Authorization" to "Basic $encoded")
    }

    private fun buildBaseUrl(server: RemoteServer): String {
        val host = server.host.trim()
        if (host.startsWith("http://") || host.startsWith("https://")) {
            val base = host.removeSuffix("/")
            return appendPortToBaseUrl(base, server.port)
        }
        val port = server.port?.let { ":$it" } ?: ""
        return "http://$host$port"
    }

    private fun appendPortToBaseUrl(baseUrl: String, port: Int?): String {
        port ?: return baseUrl

        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return baseUrl
        if (uri.port != -1) return baseUrl

        val authority = uri.rawAuthority ?: return baseUrl
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "${uri.scheme}://$authority:$port$path$query$fragment"
    }

    private fun getHttpClient(server: RemoteServer): OkHttpClient {
        val key = HttpClientKey(
            isProxyEnabled = server.isProxyEnabled,
            proxyHost = server.proxyHost,
            proxyPort = server.proxyPort,
        )
        httpClients[key]?.let { return it }

        val client = buildClient(server)
        httpClients[key] = client
        while (httpClients.size > MAX_HTTP_CLIENT_CACHE_SIZE) {
            val oldestKey = httpClients.keys.firstOrNull() ?: return client
            httpClients.remove(oldestKey)
        }
        return client
    }

    private fun buildClient(server: RemoteServer): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(BROWSE_TIMEOUT)
            .readTimeout(BROWSE_TIMEOUT)

        if (server.isProxyEnabled && server.proxyHost.isNotBlank()) {
            builder.proxy(
                Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress(server.proxyHost, server.proxyPort ?: 8080),
                ),
            )
        }

        return builder.build()
    }

    private fun applyAuth(builder: Request.Builder, server: RemoteServer) {
        if (server.username.isBlank()) return

        val credentials = "${server.username}:${server.password}"
        val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        builder.header("Authorization", "Basic $encoded")
    }

    @Suppress("NestedBlockDepth")
    private fun parsePropfindResponse(
        inputStream: InputStream,
        requestPath: String,
        baseUrl: String,
    ): List<RemoteFile> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val files = mutableListOf<RemoteFile>()
        var currentHref: String? = null
        var currentDisplayName: String? = null
        var currentContentLength: Long = 0
        var currentContentType = ""
        var isCollection = false
        var inResponse = false
        var currentTag = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "response") {
                        inResponse = true
                        currentHref = null
                        currentDisplayName = null
                        currentContentLength = 0
                        currentContentType = ""
                        isCollection = false
                    }
                    if (currentTag == "collection" && inResponse) {
                        isCollection = true
                    }
                }

                XmlPullParser.TEXT -> {
                    if (inResponse) {
                        val text = parser.text?.trim().orEmpty()
                        when (currentTag) {
                            "href" -> currentHref = text
                            "displayname" -> currentDisplayName = text
                            "getcontentlength" -> currentContentLength = text.toLongOrNull() ?: 0
                            "getcontenttype" -> currentContentType = text
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "response" && inResponse) {
                        inResponse = false
                        val href = currentHref
                        if (href != null) {
                            val path = normalizeResponsePath(
                                href = href,
                                baseUrl = baseUrl,
                                requestPath = requestPath,
                            )
                            val decodedHref = Uri.decode(path)
                            val decodedRequest = Uri.decode(requestPath)
                            val isSelf = decodedHref.removeSuffix("/") == decodedRequest.removeSuffix("/")
                            if (!isSelf) {
                                val name = currentDisplayName?.takeIf { it.isNotBlank() }
                                    ?: decodedHref.removeSuffix("/").substringAfterLast("/")
                                val filePath = if (isCollection) {
                                    path.ensureTrailingSlash()
                                } else {
                                    path
                                }
                                files.add(
                                    RemoteFile(
                                        name = name,
                                        path = filePath,
                                        isDirectory = isCollection,
                                        size = currentContentLength,
                                        contentType = currentContentType,
                                    ),
                                )
                            }
                        }
                    }
                    currentTag = ""
                }
            }
            parser.next()
        }

        return files.sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name })
    }

    private fun normalizeResponsePath(
        href: String,
        baseUrl: String,
        requestPath: String,
    ): String {
        val hrefPath = when {
            href.startsWith("http://") || href.startsWith("https://") -> {
                runCatching { URI(href).rawPath }.getOrNull().orEmpty()
            }
            href.startsWith("/") -> href
            else -> requestPath.ensureTrailingSlash() + href
        }.ifBlank { "/" }

        val basePath = runCatching { URI(baseUrl).rawPath }
            .getOrNull()
            .orEmpty()
            .removeSuffix("/")
        val path = if (
            basePath.isNotBlank() &&
            !requestPath.isSamePathOrDescendant(basePath) &&
            hrefPath.isSamePathOrDescendant(basePath)
        ) {
            hrefPath.removePrefix(basePath).ifBlank { "/" }
        } else {
            hrefPath
        }

        return path.ensureLeadingSlash()
    }

    private fun String.isSamePathOrDescendant(parentPath: String): Boolean {
        val parent = parentPath.removeSuffix("/")
        if (parent.isBlank()) return false

        val child = removeSuffix("/")
        return child == parent || child.startsWith("$parent/")
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

    private fun String.ensureLeadingSlash(): String = if (startsWith("/")) this else "/$this"

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

    private data class HttpClientKey(
        val isProxyEnabled: Boolean,
        val proxyHost: String,
        val proxyPort: Int?,
    )

    private data class DirectoryCacheKey(
        val baseUrl: String,
        val authFingerprint: String,
        val isProxyEnabled: Boolean,
        val proxyHost: String,
        val proxyPort: Int?,
        val directoryPath: String,
    )

    private data class DirectoryCacheEntry(
        val files: List<RemoteFile>,
        val createdAtMillis: Long,
    ) {
        fun isFresh(): Boolean = System.currentTimeMillis() - createdAtMillis <= DIRECTORY_CACHE_TTL_MS
    }

    companion object {
        private val BROWSE_TIMEOUT = java.time.Duration.ofSeconds(6)
        private const val DIRECTORY_CACHE_TTL_MS = 30_000L
        private const val MAX_DIRECTORY_CACHE_SIZE = 32
        private const val MAX_HTTP_CLIENT_CACHE_SIZE = 4

        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>"""
    }
}
