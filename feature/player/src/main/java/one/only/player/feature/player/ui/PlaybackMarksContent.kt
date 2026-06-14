package one.only.player.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import one.only.player.core.model.PlaybackMark
import one.only.player.core.ui.R
import one.only.player.core.ui.designsystem.NextIcons
import one.only.player.feature.player.extensions.formatted

@Composable
fun PlaybackMarksContent(
    marks: List<PlaybackMark>,
    onAddMarkClick: () -> Unit,
    onMarkClick: (PlaybackMark) -> Unit,
    onDeleteMarkClick: (PlaybackMark) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onAddMarkClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_add_playback_mark"),
        ) {
            Text(text = stringResource(R.string.add_playback_mark))
        }

        if (marks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = stringResource(R.string.no_playback_marks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = marks,
                    key = PlaybackMark::id,
                ) { mark ->
                    PlaybackMarkItem(
                        mark = mark,
                        onClick = { onMarkClick(mark) },
                        onDeleteClick = { onDeleteMarkClick(mark) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackMarkItem(
    mark: PlaybackMark,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("playback_mark_${mark.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = NextIcons.History,
                contentDescription = null,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mark.positionMs.milliseconds.formatted(),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (mark.durationMs > 0L) {
                    Text(
                        text = mark.durationMs.milliseconds.formatted(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = NextIcons.Delete,
                    contentDescription = stringResource(R.string.delete_mark),
                )
            }
        }
    }
}
