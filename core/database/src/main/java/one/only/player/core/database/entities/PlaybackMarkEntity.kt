package one.only.player.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_mark",
    indices = [
        Index(value = ["media_uri", "position_ms"]),
        Index(value = ["created_at"]),
    ],
)
data class PlaybackMarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "media_uri")
    val mediaUri: String,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    val label: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
