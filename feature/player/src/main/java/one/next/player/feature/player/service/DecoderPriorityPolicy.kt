package one.next.player.feature.player.service

import androidx.media3.exoplayer.DefaultRenderersFactory
import one.next.player.core.model.DecoderPriority

internal fun DecoderPriority.extensionRendererMode(): Int = when (this) {
    DecoderPriority.AUTOMATIC -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
    DecoderPriority.DEVICE_ONLY -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
    DecoderPriority.PREFER_DEVICE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
    DecoderPriority.PREFER_APP -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
}

internal fun DecoderPriority.logName(): String = when (this) {
    DecoderPriority.AUTOMATIC -> "AT"
    DecoderPriority.DEVICE_ONLY -> "HW"
    DecoderPriority.PREFER_DEVICE -> "HW+"
    DecoderPriority.PREFER_APP -> "SW"
}

internal fun DecoderPriority.shouldEnableDecoderFallback(): Boolean = this != DecoderPriority.DEVICE_ONLY

internal fun DecoderPriority.shouldRetryWithSoftwareDecoder(): Boolean = this == DecoderPriority.AUTOMATIC || this == DecoderPriority.PREFER_DEVICE

internal fun DecoderPriority.shouldUseAudioExtensionFallback(): Boolean = this == DecoderPriority.AUTOMATIC
