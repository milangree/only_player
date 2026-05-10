package one.next.player.core.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import one.next.player.core.model.PlayerPreferences
import one.next.player.core.ui.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoFiltersDialog(
    preferences: PlayerPreferences,
    onDismissRequest: () -> Unit,
    onPreviewPreferences: (PlayerPreferences) -> Unit,
    onConfirmPreferences: (PlayerPreferences) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dialogModifier = if (isLandscape) {
        modifier.widthIn(max = 640.dp)
    } else {
        modifier
    }
    val initialPreferences = remember { preferences }
    var draftPreferences by remember { mutableStateOf(preferences) }
    val updateDraft = { transform: (PlayerPreferences) -> PlayerPreferences ->
        val updatedPreferences = transform(draftPreferences)
        draftPreferences = updatedPreferences
        onPreviewPreferences(updatedPreferences)
    }
    val restoreAndDismiss = {
        onPreviewPreferences(initialPreferences)
        onDismissRequest()
    }
    val confirmAndDismiss = {
        onConfirmPreferences(draftPreferences)
        onDismissRequest()
    }

    NextDialog(
        modifier = dialogModifier.testTag("dialog_video_filters"),
        onDismissRequest = restoreAndDismiss,
        title = { Text(text = stringResource(R.string.video_filters)) },
        confirmButton = { DoneButton(onClick = confirmAndDismiss) },
        dismissButton = { CancelButton(onClick = restoreAndDismiss) },
        content = {
            if (isLandscape) {
                LandscapeVideoFiltersContent(
                    preferences = draftPreferences,
                    onUpdatePreferences = updateDraft,
                )
            } else {
                PortraitVideoFiltersContent(
                    preferences = draftPreferences,
                    onUpdatePreferences = updateDraft,
                )
            }
        },
    )
}

@Composable
private fun PortraitVideoFiltersContent(
    preferences: PlayerPreferences,
    onUpdatePreferences: ((PlayerPreferences) -> PlayerPreferences) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VideoFilterSlider(
            title = stringResource(R.string.video_brightness),
            value = preferences.videoBrightness,
            valueRange = PlayerPreferences.MIN_VIDEO_BRIGHTNESS..PlayerPreferences.MAX_VIDEO_BRIGHTNESS,
            valueText = signedPercent(preferences.videoBrightness),
            testTag = "slider_video_brightness",
            onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoBrightness = it) } },
        )
        VideoFilterSlider(
            title = stringResource(R.string.video_contrast),
            value = preferences.videoContrast,
            valueRange = PlayerPreferences.MIN_VIDEO_CONTRAST..PlayerPreferences.MAX_VIDEO_CONTRAST,
            valueText = signedPercent(preferences.videoContrast),
            testTag = "slider_video_contrast",
            onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoContrast = it) } },
        )
        VideoFilterSlider(
            title = stringResource(R.string.video_saturation),
            value = preferences.videoSaturation,
            valueRange = PlayerPreferences.MIN_VIDEO_SATURATION..PlayerPreferences.MAX_VIDEO_SATURATION,
            valueText = signedInteger(preferences.videoSaturation),
            testTag = "slider_video_saturation",
            onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoSaturation = it) } },
        )
        VideoFilterSlider(
            title = stringResource(R.string.video_hue),
            value = preferences.videoHue,
            valueRange = PlayerPreferences.MIN_VIDEO_HUE..PlayerPreferences.MAX_VIDEO_HUE,
            valueText = stringResource(R.string.degrees, preferences.videoHue.toInt()),
            testTag = "slider_video_hue",
            onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoHue = it) } },
        )
        VideoFilterSlider(
            title = stringResource(R.string.video_gamma),
            value = preferences.videoGamma,
            valueRange = PlayerPreferences.MIN_VIDEO_GAMMA..PlayerPreferences.MAX_VIDEO_GAMMA,
            valueText = String.format("%.2f", preferences.videoGamma),
            testTag = "slider_video_gamma",
            onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoGamma = it) } },
        )
        VideoFilterSlider(
            title = stringResource(R.string.video_sharpening),
            value = preferences.videoSharpening,
            valueRange = PlayerPreferences.DEFAULT_VIDEO_SHARPENING..PlayerPreferences.MAX_VIDEO_SHARPENING,
            valueText = stringResource(R.string.percent, (preferences.videoSharpening * 100).toInt()),
            testTag = "slider_video_sharpening",
            onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoSharpening = it) } },
        )
    }
}

@Composable
private fun LandscapeVideoFiltersContent(
    preferences: PlayerPreferences,
    onUpdatePreferences: ((PlayerPreferences) -> PlayerPreferences) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CompactVideoFilterSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.video_brightness),
                value = preferences.videoBrightness,
                valueRange = PlayerPreferences.MIN_VIDEO_BRIGHTNESS..PlayerPreferences.MAX_VIDEO_BRIGHTNESS,
                valueText = signedPercent(preferences.videoBrightness),
                testTag = "slider_video_brightness",
                onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoBrightness = it) } },
            )
            CompactVideoFilterSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.video_contrast),
                value = preferences.videoContrast,
                valueRange = PlayerPreferences.MIN_VIDEO_CONTRAST..PlayerPreferences.MAX_VIDEO_CONTRAST,
                valueText = signedPercent(preferences.videoContrast),
                testTag = "slider_video_contrast",
                onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoContrast = it) } },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CompactVideoFilterSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.video_saturation),
                value = preferences.videoSaturation,
                valueRange = PlayerPreferences.MIN_VIDEO_SATURATION..PlayerPreferences.MAX_VIDEO_SATURATION,
                valueText = signedInteger(preferences.videoSaturation),
                testTag = "slider_video_saturation",
                onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoSaturation = it) } },
            )
            CompactVideoFilterSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.video_hue),
                value = preferences.videoHue,
                valueRange = PlayerPreferences.MIN_VIDEO_HUE..PlayerPreferences.MAX_VIDEO_HUE,
                valueText = stringResource(R.string.degrees, preferences.videoHue.toInt()),
                testTag = "slider_video_hue",
                onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoHue = it) } },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CompactVideoFilterSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.video_gamma),
                value = preferences.videoGamma,
                valueRange = PlayerPreferences.MIN_VIDEO_GAMMA..PlayerPreferences.MAX_VIDEO_GAMMA,
                valueText = String.format("%.2f", preferences.videoGamma),
                testTag = "slider_video_gamma",
                onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoGamma = it) } },
            )
            CompactVideoFilterSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.video_sharpening),
                value = preferences.videoSharpening,
                valueRange = PlayerPreferences.DEFAULT_VIDEO_SHARPENING..PlayerPreferences.MAX_VIDEO_SHARPENING,
                valueText = stringResource(R.string.percent, (preferences.videoSharpening * 100).toInt()),
                testTag = "slider_video_sharpening",
                onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoSharpening = it) } },
            )
        }
    }
}

@Composable
private fun VideoFilterSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    testTag: String,
    onValueChange: (Float) -> Unit,
) {
    PreferenceSlider(
        modifier = Modifier.testTag(testTag),
        title = title,
        description = valueText,
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
    )
}

@Composable
private fun CompactVideoFilterSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    testTag: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 28.dp),
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
        )
    }
}

private fun signedPercent(value: Float): String {
    val percent = (value * 100).toInt()
    return if (percent > 0) "+$percent%" else "$percent%"
}

private fun signedInteger(value: Float): String {
    val rounded = value.toInt()
    return if (rounded > 0) "+$rounded" else "$rounded"
}
