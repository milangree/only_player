package one.only.player.core.data.repository

import java.io.File
import one.only.player.core.common.extensions.canonicalPathOrSelf
import one.only.player.core.common.extensions.prettyName
import one.only.player.core.model.FavoriteItem
import one.only.player.core.model.FavoriteTargetType
import one.only.player.core.model.Folder
import one.only.player.core.model.RemoteFile
import one.only.player.core.model.RemoteServer
import one.only.player.core.model.Video

fun Video.toFavoriteItem(parentId: Long? = null): FavoriteItem {
    val targetToken = id.takeIf { it > 0L }?.toString() ?: uriString
    return FavoriteItem(
        parentId = parentId,
        targetType = FavoriteTargetType.LOCAL_VIDEO,
        targetKey = "local:video:$targetToken",
        title = nameWithExtension,
        subtitle = path,
        localUri = uriString,
        localPath = path,
    )
}

fun Folder.toFavoriteItem(parentId: Long? = null): FavoriteItem {
    val canonicalPath = path.canonicalPathOrSelf()
    return FavoriteItem(
        parentId = parentId,
        targetType = FavoriteTargetType.LOCAL_FOLDER,
        targetKey = "local:folder:$canonicalPath",
        title = File(path).prettyName,
        subtitle = path,
        localPath = path,
    )
}

fun RemoteServer.toFavoriteRootItem(parentId: Long? = null): FavoriteItem = FavoriteItem(
    parentId = parentId,
    targetType = FavoriteTargetType.REMOTE_SERVER_ROOT,
    targetKey = "remote:root:$id",
    title = name.ifBlank { host },
    subtitle = "${protocol.name} · $host",
    remoteServerId = id,
    remoteProtocol = protocol,
    remotePath = null,
    remoteServerName = name.ifBlank { host },
)

fun RemoteFile.toRemoteFavoriteItem(
    server: RemoteServer,
    parentId: Long? = null,
): FavoriteItem {
    val targetType = if (isDirectory) {
        FavoriteTargetType.REMOTE_DIRECTORY
    } else {
        FavoriteTargetType.REMOTE_FILE
    }
    val targetKind = if (isDirectory) "dir" else "file"
    return FavoriteItem(
        parentId = parentId,
        targetType = targetType,
        targetKey = "remote:$targetKind:${server.id}:$path",
        title = name,
        subtitle = "${server.name.ifBlank { server.host }} · $path",
        remoteServerId = server.id,
        remoteProtocol = server.protocol,
        remotePath = path,
        remoteServerName = server.name.ifBlank { server.host },
    )
}

fun RemoteServer.toRemoteDirectoryFavoriteItem(
    path: String,
    title: String,
    parentId: Long? = null,
): FavoriteItem = FavoriteItem(
    parentId = parentId,
    targetType = FavoriteTargetType.REMOTE_DIRECTORY,
    targetKey = "remote:dir:$id:$path",
    title = title.ifBlank { path },
    subtitle = "${name.ifBlank { host }} · $path",
    remoteServerId = id,
    remoteProtocol = protocol,
    remotePath = path,
    remoteServerName = name.ifBlank { host },
)
