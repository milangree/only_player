package one.next.player.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import one.next.player.core.model.Resume
import one.next.player.core.ui.R

@Composable
fun Resume.name(): String {
    val stringRes = when (this) {
        Resume.YES -> R.string.yes
        Resume.NO -> R.string.no
    }

    return stringResource(id = stringRes)
}

val Resume.isEnabled: Boolean
    get() = when (this) {
        Resume.YES -> true
        Resume.NO -> false
    }
