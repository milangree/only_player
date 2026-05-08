package one.next.player.feature.player.service

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.SeparableConvolution

@OptIn(UnstableApi::class)
class VideoSharpeningEffect(
    strength: Float,
) : SeparableConvolution() {

    private val clampedStrength = strength.coerceIn(0f, 1f)

    private val convolution = object : ConvolutionFunction1D {
        override fun domainStart(): Float = -1f

        override fun domainEnd(): Float = 1f

        override fun value(samplePosition: Float): Float = when {
            samplePosition < -0.5f -> -0.25f * clampedStrength
            samplePosition > 0.5f -> -0.25f * clampedStrength
            else -> 1f + 0.5f * clampedStrength
        }
    }

    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D = convolution

    override fun isNoOp(
        inputWidth: Int,
        inputHeight: Int,
    ): Boolean = clampedStrength <= 0f
}
