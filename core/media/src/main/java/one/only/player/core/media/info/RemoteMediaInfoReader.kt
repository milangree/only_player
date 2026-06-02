package one.only.player.core.media.info

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.anilbeesetti.nextlib.mediainfo.AudioStream
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import io.github.anilbeesetti.nextlib.mediainfo.SubtitleStream
import io.github.anilbeesetti.nextlib.mediainfo.VideoStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import one.only.player.core.common.Dispatcher
import one.only.player.core.common.Logger
import one.only.player.core.common.NextDispatchers
import one.only.player.core.model.AudioStreamInfo
import one.only.player.core.model.SubtitleStreamInfo
import one.only.player.core.model.VideoStreamInfo

class RemoteMediaInfoReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(NextDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun read(
        probeUrl: String?,
        documentUri: Uri,
    ): Result<RemoteMediaInfo> = withContext(ioDispatcher) {
        var lastError: Throwable? = null
        val sources = listOfNotNull(
            probeUrl?.let { RemoteMediaInfoSource.Url(it) },
            RemoteMediaInfoSource.Document(documentUri),
        )

        for (source in sources) {
            val result = readSource(source)
            if (result.isSuccess) return@withContext result
            lastError = result.exceptionOrNull()
        }

        Result.failure(lastError ?: IllegalStateException("No remote media info source"))
    }

    private fun readSource(source: RemoteMediaInfoSource): Result<RemoteMediaInfo> = runCatching {
        val mediaInfo = when (source) {
            is RemoteMediaInfoSource.Url -> MediaInfoBuilder().from(source.url).build()
            is RemoteMediaInfoSource.Document -> MediaInfoBuilder().from(context, source.uri).build()
        } ?: throw NullPointerException("MediaInfoBuilder returned null")

        try {
            RemoteMediaInfo(
                format = mediaInfo.format,
                duration = mediaInfo.duration,
                videoStream = mediaInfo.videoStream?.toVideoStreamInfo(),
                audioStreams = mediaInfo.audioStreams.map(AudioStream::toAudioStreamInfo),
                subtitleStreams = mediaInfo.subtitleStreams.map(SubtitleStream::toSubtitleStreamInfo),
            )
        } finally {
            mediaInfo.release()
        }
    }.onFailure { throwable ->
        Logger.error(TAG, "Failed to read remote media info from ${source.logName}", throwable)
    }

    private sealed interface RemoteMediaInfoSource {
        val logName: String

        data class Url(val url: String) : RemoteMediaInfoSource {
            override val logName: String = "url"
        }

        data class Document(val uri: Uri) : RemoteMediaInfoSource {
            override val logName: String = "document"
        }
    }

    companion object {
        private const val TAG = "RemoteMediaInfoReader"
    }
}

data class RemoteMediaInfo(
    val format: String?,
    val duration: Long,
    val videoStream: VideoStreamInfo?,
    val audioStreams: List<AudioStreamInfo>,
    val subtitleStreams: List<SubtitleStreamInfo>,
)

private fun VideoStream.toVideoStreamInfo() = VideoStreamInfo(
    index = index,
    title = title,
    codecName = codecName,
    language = language,
    disposition = disposition,
    bitRate = bitRate,
    frameRate = frameRate,
    frameWidth = frameWidth,
    frameHeight = frameHeight,
)

private fun AudioStream.toAudioStreamInfo() = AudioStreamInfo(
    index = index,
    title = title,
    codecName = codecName,
    language = language,
    disposition = disposition,
    bitRate = bitRate,
    sampleFormat = sampleFormat,
    sampleRate = sampleRate,
    channels = channels,
    channelLayout = channelLayout,
)

private fun SubtitleStream.toSubtitleStreamInfo() = SubtitleStreamInfo(
    index = index,
    title = title,
    codecName = codecName,
    language = language,
    disposition = disposition,
)
