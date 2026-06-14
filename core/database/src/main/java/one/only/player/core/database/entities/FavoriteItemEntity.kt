package one.only.player.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorite_item",
    indices = [
        Index(value = ["target_key"], unique = true),
        Index(value = ["parent_id"]),
        Index(value = ["target_type"]),
    ],
)
data class FavoriteItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "parent_id")
    val parentId: Long?,
    @ColumnInfo(name = "target_type")
    val targetType: String,
    @ColumnInfo(name = "target_key")
    val targetKey: String,
    val title: String,
    val subtitle: String,
    @ColumnInfo(name = "local_uri")
    val localUri: String?,
    @ColumnInfo(name = "local_path")
    val localPath: String?,
    @ColumnInfo(name = "remote_server_id")
    val remoteServerId: Long?,
    @ColumnInfo(name = "remote_protocol")
    val remoteProtocol: String?,
    @ColumnInfo(name = "remote_path")
    val remotePath: String?,
    @ColumnInfo(name = "remote_server_name")
    val remoteServerName: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Long,
)
