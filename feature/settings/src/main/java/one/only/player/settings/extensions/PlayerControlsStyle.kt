package one.only.player.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import one.only.player.core.model.PlayerControlsStyle
import one.only.player.core.ui.R

@Composable
fun PlayerControlsStyle.name(): String {
    val stringRes = when (this) {
        PlayerControlsStyle.LEGACY -> R.string.player_controls_style_legacy
        PlayerControlsStyle.MODERN -> R.string.player_controls_style_modern
    }
    return stringResource(stringRes)
}
