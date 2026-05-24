package one.only.player.feature.player.service.audio

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import one.only.player.core.common.Logger
import one.only.player.core.model.PlayerPreferences

internal class AudioEffectsCoordinator {

    val volumeNormalizationAudioProcessor = VolumeNormalizationAudioProcessor()

    private var loudnessEnhancer: LoudnessEnhancer? = null
    var requestedVolumeGain: Int = 0
        private set

    val isLoudnessGainSupported: Boolean
        get() = loudnessEnhancer != null

    fun setEnhancerTargetGain(
        gain: Int,
        preferences: PlayerPreferences,
        audioSessionId: Int,
    ) {
        requestedVolumeGain = gain.coerceAtLeast(0)
        if (loudnessEnhancer == null && preferences.isVolumeBoostEnabled) {
            initializeLoudnessEnhancer(
                audioSessionId = audioSessionId,
                preferences = preferences,
            )
        }
        applyLoudnessEnhancerGain()
    }

    fun initializeLoudnessEnhancer(
        audioSessionId: Int,
        preferences: PlayerPreferences,
    ) {
        if (!preferences.isVolumeBoostEnabled) return
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            releaseLoudnessEnhancer()
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)
            Logger.debug(TAG, "Loudness enhancer initialized: boost=true")
            applyLoudnessEnhancerGain()
        } catch (exception: Exception) {
            Logger.error(TAG, "Failed to initialize loudness enhancer", exception)
            loudnessEnhancer = null
        }
    }

    fun releaseLoudnessEnhancer() {
        val enhancer = loudnessEnhancer ?: return
        try {
            enhancer.enabled = false
        } catch (exception: Exception) {
            Logger.error(TAG, "Failed to disable loudness enhancer", exception)
        }
        try {
            enhancer.release()
        } catch (exception: Exception) {
            Logger.error(TAG, "Failed to release loudness enhancer", exception)
        } finally {
            loudnessEnhancer = null
        }
    }

    fun applyVolumeNormalization(isEnabled: Boolean) {
        volumeNormalizationAudioProcessor.isEnabled = isEnabled
        Logger.debug(TAG, "Apply volume normalization: enabled=$isEnabled")
    }

    private fun applyLoudnessEnhancerGain() {
        val enhancer = loudnessEnhancer ?: return
        val gain = requestedVolumeGain

        try {
            enhancer.setTargetGain(gain)
            enhancer.enabled = gain > 0
            Logger.debug(TAG, "Apply loudness gain: requested=$requestedVolumeGain, enabled=${gain > 0}")
        } catch (exception: Exception) {
            Logger.error(TAG, "Failed to apply loudness enhancer gain", exception)
        }
    }

    private companion object {
        private const val TAG = "AudioEffectsCoordinator"
    }
}
