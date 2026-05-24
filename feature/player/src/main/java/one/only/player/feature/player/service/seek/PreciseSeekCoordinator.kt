package one.only.player.feature.player.service.seek

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.SeekMap
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.only.player.core.common.Logger
import one.only.player.core.common.extensions.getPath
import one.only.player.feature.player.engine.media3.MkvCuePoint
import one.only.player.feature.player.engine.media3.MkvCuesParser
import one.only.player.feature.player.engine.media3.buildSeekMapFromCues
import one.only.player.feature.player.extensions.copy
import one.only.player.feature.player.extensions.isApproximateSeekEnabled
import one.only.player.feature.player.extensions.positionMs

internal class PreciseSeekCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentPlayerProvider: () -> ExoPlayer?,
    private val createMediaSource: (MediaItem) -> androidx.media3.exoplayer.source.MediaSource,
    private val resolvePlaybackStateUri: suspend (MediaItem) -> String,
    private val updatePlaybackPosition: suspend (String, Long) -> Unit,
    private val mediaLogSummary: (String) -> String,
) {

    private var pendingPromotionJob: Job? = null
    private var requestId = 0L
    private var pendingStartupResumeToken: String? = null
    private var pendingStartupResumePositionMs: Long? = null

    private val seekMapCache = ConcurrentHashMap<String, SeekMap>()
    private val cueParseJobs = ConcurrentHashMap<String, Deferred<SeekMap?>>()
    private val preciseSeekMediaIds = ConcurrentHashMap.newKeySet<String>()

    operator fun contains(mediaId: String): Boolean = mediaId in preciseSeekMediaIds

    fun cachedSeekMap(mediaId: String): SeekMap? = seekMapCache[mediaId]

    fun clearPreciseMediaIds() {
        preciseSeekMediaIds.clear()
    }

    fun resetForMediaItem() {
        pendingPromotionJob?.cancel()
        pendingPromotionJob = null
        requestId++
        pendingStartupResumeToken = null
        pendingStartupResumePositionMs = null
    }

    fun release() {
        pendingPromotionJob?.cancel()
        pendingPromotionJob = null
        requestId++
        cueParseJobs.clear()
    }

    fun prepareCachedMediaItems(mediaItems: List<MediaItem>) {
        mediaItems.forEach { mediaItem ->
            if (!mediaItem.mediaMetadata.isApproximateSeekEnabled) return@forEach
            restoreCachedSeekMap(mediaItem)?.let { seekMap ->
                seekMapCache[mediaItem.mediaId] = seekMap
                preciseSeekMediaIds.add(mediaItem.mediaId)
            }
        }
    }

    fun restoreCachedSeekMapForStartup(mediaItem: MediaItem): SeekMap? {
        val seekMap = restoreCachedSeekMap(mediaItem) ?: return null
        seekMapCache[mediaItem.mediaId] = seekMap
        return seekMap
    }

    fun markPrecise(mediaId: String) {
        preciseSeekMediaIds.add(mediaId)
    }

    fun scheduleCueCache(mediaItem: MediaItem): Deferred<SeekMap?> {
        val mediaId = mediaItem.mediaId
        seekMapCache[mediaId]?.let { return CompletableDeferred(it) }
        cueParseJobs[mediaId]?.let { return it }

        val restoredSeekMap = restoreCachedSeekMap(mediaItem)
        if (restoredSeekMap != null) {
            seekMapCache[mediaId] = restoredSeekMap
            return CompletableDeferred(restoredSeekMap)
        }

        val durationMs = mediaItem.mediaMetadata.durationMs
        if (durationMs == null) return CompletableDeferred(null)
        val uri = Uri.parse(mediaId)

        val parseJob = scope.async(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val cuePoints = MkvCuesParser.parse(context, uri)
            val elapsed = System.currentTimeMillis() - startTime

            if (cuePoints == null) {
                Logger.debug(TAG, "MKV Cues pre-parse returned null for $mediaId (${elapsed}ms)")
                return@async null
            }

            val durationUs = durationMs * 1_000L
            val seekMap = buildSeekMapFromCues(cuePoints, durationUs)
            seekMapCache[mediaId] = seekMap
            persistSeekMap(uri, cuePoints, durationUs)
            Logger.info(
                TAG,
                "MKV Cues pre-parsed: ${cuePoints.size} cue points in ${elapsed}ms for $mediaId",
            )
            seekMap
        }
        parseJob.invokeOnCompletion {
            cueParseJobs.remove(mediaId, parseJob)
        }
        cueParseJobs[mediaId] = parseJob
        return parseJob
    }

    fun shouldUsePreciseStartupResume(positionMs: Long): Boolean = positionMs >= STARTUP_PRECISE_RESUME_THRESHOLD_MS

    fun deferStartupResume(
        mediaId: String,
        positionMs: Long,
    ) {
        pendingStartupResumeToken = mediaId
        pendingStartupResumePositionMs = positionMs
    }

    fun continueDeferredStartupResume(currentMediaItem: MediaItem) {
        val player = currentPlayerProvider() ?: return
        val mediaId = currentMediaItem.mediaId
        if (pendingStartupResumeToken != mediaId) return
        if (!currentMediaItem.mediaMetadata.isApproximateSeekEnabled) {
            clearPendingStartupResume()
            return
        }

        val targetPosition = pendingStartupResumePositionMs ?: currentMediaItem.mediaMetadata.positionMs ?: return
        if (targetPosition < STARTUP_PRECISE_RESUME_THRESHOLD_MS) {
            clearPendingStartupResume()
            return
        }
        if (player.currentPosition >= targetPosition - 1_000L) {
            clearPendingStartupResume()
            return
        }

        pendingPromotionJob?.cancel()
        pendingPromotionJob = scope.launch(Dispatchers.IO) {
            val seekMap = seekMapCache[mediaId]
                ?: restoreCachedSeekMap(currentMediaItem)
                ?: scheduleCueCache(currentMediaItem).await()
                ?: return@launch

            seekMapCache[mediaId] = seekMap
            withContext(Dispatchers.Main) {
                val currentPlayer = currentPlayerProvider() ?: return@withContext
                val current = currentPlayer.currentMediaItem ?: return@withContext
                if (current.mediaId != mediaId) return@withContext
                if (pendingStartupResumeToken != mediaId) return@withContext
                clearPendingStartupResume()
                Logger.info(TAG, "Resume deferred precise-seek media item=$mediaId position=$targetPosition")
                promoteCurrentItemToPreciseSeek(targetPosition)
            }
        }
    }

    fun seekWithinCurrentItem(
        player: Player,
        targetPositionMs: Long,
    ) {
        val currentItem = player.currentMediaItem ?: return
        if (currentItem.mediaMetadata.isApproximateSeekEnabled) {
            val nextRequestId = ++requestId
            scope.launch { promoteCurrentItemToPreciseSeek(targetPositionMs, nextRequestId) }
            return
        }
        requestId++
        player.seekTo(targetPositionMs)
    }

    suspend fun requestSeekForCurrentItem(targetPositionMs: Long): SessionResult {
        val player = currentPlayerProvider() ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val currentItem = player.currentMediaItem ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val maxPosition = currentItem.mediaMetadata.durationMs
            ?.takeIf { it > 0L }
            ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val targetPosition = maxPosition?.let { targetPositionMs.coerceIn(0L, it) } ?: targetPositionMs.coerceAtLeast(0L)

        if (!currentItem.mediaMetadata.isApproximateSeekEnabled) {
            Logger.info(TAG, "Precise seek direct media=${mediaLogSummary(currentItem.mediaId)} target=$targetPosition")
            player.seekTo(targetPosition)
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }

        val startPosition = player.currentPosition.takeIf { it != C.TIME_UNSET } ?: 0L
        val startDelta = kotlin.math.abs(targetPosition - startPosition)
        if (startDelta < FAST_SEEK_MIN_DELTA_MS) {
            Logger.info(
                TAG,
                "Skip precise promotion mediaId=${currentItem.mediaId} target=$targetPosition start=$startPosition",
            )
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }

        Logger.info(
            TAG,
            "Promote to precise seek mediaId=${currentItem.mediaId} start=$startPosition target=$targetPosition",
        )
        return promoteCurrentItemToPreciseSeek(targetPosition)
    }

    suspend fun promoteCurrentItemToPreciseSeek(
        targetPositionMs: Long,
        currentRequestId: Long = ++requestId,
    ): SessionResult {
        val player = currentPlayerProvider() ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val initialItem = player.currentMediaItem ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val maxPosition = initialItem.mediaMetadata.durationMs
            ?.takeIf { it > 0L }
            ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val targetPosition = maxPosition?.let { targetPositionMs.coerceIn(0L, it) } ?: targetPositionMs.coerceAtLeast(0L)

        if (!initialItem.mediaMetadata.isApproximateSeekEnabled || initialItem.mediaId in preciseSeekMediaIds) {
            Logger.info(TAG, "Precise seek direct media=${mediaLogSummary(initialItem.mediaId)} target=$targetPosition")
            player.seekTo(targetPosition)
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }

        val seekMap = seekMapCache[initialItem.mediaId]
            ?: withContext(Dispatchers.IO) { scheduleCueCache(initialItem).await() }
        if (currentRequestId != requestId) {
            return SessionResult(SessionError.ERROR_BAD_VALUE)
        }

        val currentItem = player.currentMediaItem ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val currentIndex = player.currentMediaItemIndex
        if (currentItem.mediaId != initialItem.mediaId || currentIndex !in 0 until player.mediaItemCount) {
            return SessionResult(SessionError.ERROR_BAD_VALUE)
        }
        if (!currentItem.mediaMetadata.isApproximateSeekEnabled || currentItem.mediaId in preciseSeekMediaIds) {
            Logger.info(TAG, "Precise seek direct media=${mediaLogSummary(currentItem.mediaId)} target=$targetPosition")
            player.seekTo(targetPosition)
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }
        if (seekMap == null) {
            Logger.info(TAG, "Precise seek postponed media=${mediaLogSummary(currentItem.mediaId)} target=$targetPosition")
            player.seekTo(targetPosition)
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }
        seekMapCache[currentItem.mediaId] = seekMap

        val updatedMediaItem = currentItem.copy(
            positionMs = targetPosition,
            isApproximateSeekEnabled = false,
        )
        preciseSeekMediaIds.add(currentItem.mediaId)
        val shouldPlayWhenReady = player.playWhenReady
        Logger.info(
            TAG,
            "Promote current item to precise seek media=${mediaLogSummary(currentItem.mediaId)} target=$targetPosition hasCachedSeekMap=true",
        )
        player.addMediaSource(currentIndex + 1, createMediaSource(updatedMediaItem))
        player.seekTo(currentIndex + 1, targetPosition)
        player.removeMediaItem(currentIndex)
        player.prepare()
        player.playWhenReady = shouldPlayWhenReady
        scope.launch {
            val playbackStateUri = resolvePlaybackStateUri(currentItem)
            updatePlaybackPosition(playbackStateUri, targetPosition)
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun clearPendingStartupResume() {
        pendingStartupResumeToken = null
        pendingStartupResumePositionMs = null
    }

    private fun cueCacheFile(uri: Uri): File? {
        val path = runCatching {
            when (uri.scheme) {
                ContentResolver.SCHEME_FILE -> uri.toFile().absolutePath
                ContentResolver.SCHEME_CONTENT -> context.getPath(uri)
                else -> null
            }
        }.getOrNull() ?: return null
        val sourceFile = File(path)
        if (!sourceFile.exists()) return null
        val cacheKey = sourceFile.absolutePath.hashCode().toUInt().toString(16)
        val cacheDir = File(context.cacheDir, "mkv-cues")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, "mkv-cues-$cacheKey.bin")
    }

    private fun persistSeekMap(
        uri: Uri,
        cuePoints: List<MkvCuePoint>,
        durationUs: Long,
    ) {
        val sourceFile = resolveLocalFile(uri) ?: return
        val cacheFile = cueCacheFile(uri) ?: return
        runCatching {
            DataOutputStream(cacheFile.outputStream().buffered()).use { output ->
                output.writeInt(MKV_CUES_CACHE_MAGIC)
                output.writeLong(sourceFile.length())
                output.writeLong(sourceFile.lastModified())
                output.writeLong(durationUs)
                output.writeInt(cuePoints.size)
                cuePoints.forEach { cuePoint ->
                    output.writeLong(cuePoint.timeUs)
                    output.writeLong(cuePoint.clusterPosition)
                }
            }
        }.onFailure {
            cacheFile.delete()
            Logger.debug(TAG, "Failed to persist MKV cue cache for $uri")
        }
    }

    private fun restoreCachedSeekMap(mediaItem: MediaItem): SeekMap? {
        val uri = Uri.parse(mediaItem.mediaId)
        val sourceFile = resolveLocalFile(uri) ?: return null
        val cacheFile = cueCacheFile(uri) ?: return null
        if (!cacheFile.exists()) return null

        return runCatching {
            DataInputStream(cacheFile.inputStream().buffered()).use { input ->
                val magic = input.readInt()
                if (magic != MKV_CUES_CACHE_MAGIC) return@runCatching null
                val fileSize = input.readLong()
                val lastModified = input.readLong()
                if (fileSize != sourceFile.length() || lastModified != sourceFile.lastModified()) {
                    return@runCatching null
                }
                val durationUs = input.readLong()
                val count = input.readInt()
                val cuePoints = List(count) {
                    MkvCuePoint(
                        timeUs = input.readLong(),
                        clusterPosition = input.readLong(),
                    )
                }
                buildSeekMapFromCues(cuePoints, durationUs)
            }
        }.getOrNull()
    }

    private fun resolveLocalFile(uri: Uri): File? = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> runCatching { uri.toFile() }.getOrNull()
        ContentResolver.SCHEME_CONTENT -> context.getPath(uri)?.let(::File)
        else -> null
    }?.takeIf(File::exists)

    private companion object {
        private const val TAG = "PreciseSeekCoordinator"
        private const val FAST_SEEK_MIN_DELTA_MS = 2_000L
        private const val STARTUP_PRECISE_RESUME_THRESHOLD_MS = 10_000L
        private const val MKV_CUES_CACHE_MAGIC = 0x4E505145
    }
}
