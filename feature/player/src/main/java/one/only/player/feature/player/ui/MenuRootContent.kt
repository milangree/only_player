package one.only.player.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import one.only.player.core.ui.R
import one.only.player.core.ui.designsystem.NextIcons

@Composable
fun MenuRootContent(
    isLockEnabled: Boolean,
    isMuted: Boolean,
    isAmbienceModeEnabled: Boolean,
    isVideoMirrored: Boolean,
    isPipSupported: Boolean,
    isTakingScreenshot: Boolean,
    onNavigate: (MenuRoute) -> Unit,
    onLockClick: () -> Unit,
    onMuteClick: () -> Unit,
    onAmbienceClick: () -> Unit,
    onMirrorVideoClick: () -> Unit,
    onPictureInPictureClick: () -> Unit,
    onScreenshotClick: () -> Unit,
    onPlayInBackgroundClick: () -> Unit,
    onLoopClick: () -> Unit,
    onShuffleClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MenuItemRow(
            icon = NextIcons.Subtitle,
            text = stringResource(R.string.select_subtitle_track),
            testTag = "menu_item_subtitle",
            onClick = { onNavigate(MenuRoute.Subtitle) },
        )
        MenuItemRow(
            icon = NextIcons.Audio,
            text = stringResource(R.string.select_audio_track),
            testTag = "menu_item_audio",
            onClick = { onNavigate(MenuRoute.Audio) },
        )
        MenuItemRow(
            icon = NextIcons.Frame,
            text = stringResource(R.string.video_zoom),
            testTag = "menu_item_video_scale",
            onClick = { onNavigate(MenuRoute.VideoContentScale) },
        )
        MenuItemRow(
            icon = NextIcons.Decoder,
            text = stringResource(R.string.decoder_priority),
            testTag = "menu_item_decoder",
            onClick = { onNavigate(MenuRoute.Decoder) },
        )
        MenuItemRow(
            icon = NextIcons.Sensitivity,
            text = stringResource(R.string.video_filters),
            testTag = "menu_item_video_filters",
            onClick = { onNavigate(MenuRoute.VideoFilters) },
        )
        MenuItemRow(
            icon = NextIcons.Timer,
            text = stringResource(R.string.sleep_timer),
            testTag = "menu_item_sleep_timer",
            onClick = { onNavigate(MenuRoute.SleepTimer) },
        )
        MenuItemRow(
            icon = NextIcons.History,
            text = stringResource(R.string.playback_marks),
            testTag = "menu_item_playback_marks",
            onClick = { onNavigate(MenuRoute.PlaybackMarks) },
        )
        MenuItemRow(
            icon = NextIcons.Lock,
            text = stringResource(if (isLockEnabled) R.string.controls_unlock else R.string.controls_lock),
            testTag = "menu_item_lock",
            onClick = onLockClick,
        )
        MenuItemRow(
            icon = NextIcons.VolumeUp.takeIf { !isMuted },
            iconPainter = painterResource(R.drawable.ic_mute).takeIf { isMuted },
            text = stringResource(if (isMuted) R.string.controls_unmute else R.string.controls_mute),
            testTag = "menu_item_mute",
            onClick = onMuteClick,
            isSelected = isMuted,
        )
        MenuItemRow(
            icon = NextIcons.Style,
            text = stringResource(R.string.ambience_mode),
            testTag = "menu_item_ambience",
            onClick = onAmbienceClick,
            isSelected = isAmbienceModeEnabled,
        )
        MenuItemRow(
            icon = NextIcons.Size,
            text = stringResource(R.string.mirror_video),
            testTag = "menu_item_mirror_video",
            onClick = onMirrorVideoClick,
            isSelected = isVideoMirrored,
        )
        if (isPipSupported) {
            MenuItemRow(
                icon = NextIcons.Pip,
                text = stringResource(R.string.pip_settings),
                testTag = "menu_item_pip",
                onClick = onPictureInPictureClick,
            )
        }
        MenuItemRow(
            icon = NextIcons.Screenshot,
            text = stringResource(R.string.take_screenshot),
            testTag = "menu_item_screenshot",
            onClick = onScreenshotClick,
            isEnabled = !isTakingScreenshot,
        )
        MenuItemRow(
            icon = NextIcons.Headset,
            text = stringResource(R.string.background_play),
            testTag = "menu_item_background",
            onClick = onPlayInBackgroundClick,
        )
        MenuItemRow(
            icon = NextIcons.Loop,
            text = stringResource(R.string.loop_mode),
            testTag = "menu_item_loop",
            onClick = onLoopClick,
        )
        MenuItemRow(
            icon = NextIcons.Shuffle,
            text = stringResource(R.string.shuffle),
            testTag = "menu_item_shuffle",
            onClick = onShuffleClick,
        )
    }
}

@Composable
private fun MenuItemRow(
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    text: String,
    testTag: String,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    isEnabled: Boolean = true,
) {
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .testTag(testTag),
        onClick = onClick,
        enabled = isEnabled,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                )

                iconPainter != null -> Icon(
                    painter = iconPainter,
                    contentDescription = null,
                    tint = contentColor,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            if (isSelected) {
                Icon(
                    imageVector = NextIcons.Check,
                    contentDescription = null,
                    tint = contentColor,
                )
            }
        }
    }
}
