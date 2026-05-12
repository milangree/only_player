package one.next.player.feature.player.service

import one.next.player.core.model.DecoderPriority

internal data class VideoEffectsState(
    val filters: VideoFilterPreferences,
    val decoderPriority: DecoderPriority,
    val isPipelineInitialized: Boolean = false,
)
