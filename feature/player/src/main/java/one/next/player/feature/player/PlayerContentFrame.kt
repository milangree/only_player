package one.next.player.feature.player

import android.graphics.Rect
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.delay
import one.next.player.core.common.Logger
import one.next.player.core.model.DecoderPriority
import one.next.player.feature.player.extensions.toContentScale
import one.next.player.feature.player.state.ControlsVisibilityState
import one.next.player.feature.player.state.PictureInPictureState
import one.next.player.feature.player.state.SeekGestureState
import one.next.player.feature.player.state.TapGestureState
import one.next.player.feature.player.state.VideoZoomAndContentScaleState
import one.next.player.feature.player.state.VolumeAndBrightnessGestureState
import one.next.player.feature.player.ui.PlayerGestures
import one.next.player.feature.player.ui.ShutterView
import one.next.player.feature.player.ui.SubtitleConfiguration
import one.next.player.feature.player.ui.SubtitleView

@OptIn(UnstableApi::class)
@Composable
fun PlayerContentFrame(
    modifier: Modifier = Modifier,
    player: Player,
    pictureInPictureState: PictureInPictureState,
    controlsVisibilityState: ControlsVisibilityState,
    tapGestureState: TapGestureState,
    seekGestureState: SeekGestureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    volumeAndBrightnessGestureState: VolumeAndBrightnessGestureState,
    subtitleConfiguration: SubtitleConfiguration,
    decoderPriority: DecoderPriority,
    isGesturesEnabled: Boolean = true,
) {
    // 底层播放器切换后，SurfaceView 需重建以重新绑定视频输出
    var surfaceRefreshKey by remember { mutableIntStateOf(0) }
    var previousDecoderPriority by remember { mutableStateOf(decoderPriority) }
    LaunchedEffect(decoderPriority) {
        if (previousDecoderPriority == decoderPriority) return@LaunchedEffect
        previousDecoderPriority = decoderPriority
        surfaceRefreshKey++
        delay(120)
        surfaceRefreshKey++
    }
    val presentationState = rememberPresentationState(player)
    val density = LocalDensity.current
    var lastLoggedSurfaceLayout by remember { mutableStateOf("") }

    // presentationState.videoSizeDp 依赖 onVideoSizeChanged，ASS wrapper 不触发该回调
    // 从 metadata extras 中的视频尺寸作为后备
    val sourceSizeDp = presentationState.videoSizeDp?.let { size ->
        size.copy(
            width = with(density) { size.width.toDp().value },
            height = with(density) { size.height.toDp().value },
        )
    } ?: run {
        val w = videoZoomAndContentScaleState.metadataVideoWidth.toFloat()
        val h = videoZoomAndContentScaleState.metadataVideoHeight.toFloat()
        val rotation = videoZoomAndContentScaleState.metadataVideoRotation
        if (w <= 0f || h <= 0f) return@run null
        val (dw, dh) = if (rotation == 90 || rotation == 270) h to w else w to h
        Size(
            width = with(density) { dw.toDp().value },
            height = with(density) { dh.toDp().value },
        )
    }

    key(surfaceRefreshKey) {
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = modifier
                .resizeWithContentScale(
                    contentScale = videoZoomAndContentScaleState.videoContentScale.toContentScale(),
                    sourceSizeDp = sourceSizeDp,
                )
                .onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    val rect = Rect(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt(),
                    )
                    val surfaceLayoutKey = "${rect.width()}x${rect.height()}@${rect.left},${rect.top}:${videoZoomAndContentScaleState.videoContentScale}:${sourceSizeDp?.width}x${sourceSizeDp?.height}:$surfaceRefreshKey"
                    if (surfaceLayoutKey != lastLoggedSurfaceLayout) {
                        lastLoggedSurfaceLayout = surfaceLayoutKey
                        Logger.info(
                            TAG,
                            "Player surface layout size=${rect.width()}x${rect.height()} left=${rect.left} top=${rect.top} contentScale=${videoZoomAndContentScaleState.videoContentScale} sourceDp=${sourceSizeDp?.width}x${sourceSizeDp?.height} coverSurface=${presentationState.coverSurface} refresh=$surfaceRefreshKey",
                        )
                    }
                    pictureInPictureState.updateVideoViewRect(rect)
                }
                .graphicsLayer {
                    scaleX = videoZoomAndContentScaleState.zoom
                    scaleY = videoZoomAndContentScaleState.zoom
                    translationX = videoZoomAndContentScaleState.offset.x
                    translationY = videoZoomAndContentScaleState.offset.y
                },
        )
    }

    PlayerGestures(
        controlsVisibilityState = controlsVisibilityState,
        tapGestureState = tapGestureState,
        pictureInPictureState = pictureInPictureState,
        seekGestureState = seekGestureState,
        videoZoomAndContentScaleState = videoZoomAndContentScaleState,
        volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
        isEnabled = isGesturesEnabled,
    )

    if (!presentationState.coverSurface) {
        SubtitleView(
            modifier = Modifier.resizeWithContentScale(
                contentScale = videoZoomAndContentScaleState.videoContentScale.toContentScale(),
                sourceSizeDp = sourceSizeDp,
            ),
            player = player,
            isInPictureInPictureMode = pictureInPictureState.isInPictureInPictureMode,
            configuration = subtitleConfiguration,
        )
    }

    if (presentationState.coverSurface) {
        ShutterView()
    }
}

private const val TAG = "PlayerContentFrame"
