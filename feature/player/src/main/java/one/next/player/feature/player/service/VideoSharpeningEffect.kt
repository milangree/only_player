package one.next.player.feature.player.service

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

@OptIn(UnstableApi::class)
class VideoSharpeningEffect(
    strength: Float,
) : GlEffect {

    private val clampedStrength = strength.coerceIn(0f, 1f)

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = VideoSharpeningShaderProgram(
        useHdr = useHdr,
        strength = clampedStrength,
    )

    override fun isNoOp(
        inputWidth: Int,
        inputHeight: Int,
    ): Boolean = clampedStrength <= 0f

    private class VideoSharpeningShaderProgram(
        useHdr: Boolean,
        strength: Float,
    ) : BaseGlShaderProgram(useHdr, 1) {

        private val sharpness = strength * SHARPNESS_SCALE
        private val glProgram = createGlProgram()

        init {
            val identityMatrix = GlUtil.create4x4IdentityMatrix()
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                POSITION_COMPONENT_COUNT,
            )
            glProgram.setFloatsUniform("uTransformationMatrix", identityMatrix)
            glProgram.setFloatsUniform("uTexTransformationMatrix", identityMatrix)
            glProgram.setFloatUniform("uSharpness", sharpness)
        }

        override fun configure(
            inputWidth: Int,
            inputHeight: Int,
        ): Size {
            glProgram.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(
                    1f / inputWidth,
                    1f / inputHeight,
                ),
            )
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(
            inputTexId: Int,
            presentationTimeUs: Long,
        ) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
                GlUtil.checkGlError()
            } catch (exception: GlUtil.GlException) {
                throw VideoFrameProcessingException(exception, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (exception: GlUtil.GlException) {
                throw VideoFrameProcessingException(exception)
            }
        }

        private fun createGlProgram(): GlProgram = try {
            GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        } catch (exception: GlUtil.GlException) {
            throw VideoFrameProcessingException(exception)
        }
    }

    private companion object {
        private const val POSITION_COMPONENT_COUNT = 4
        private const val VERTEX_COUNT = 4
        private const val SHARPNESS_SCALE = 1.25f

        private const val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexSamplingCoord;

            void main() {
              gl_Position = uTransformationMatrix * aFramePosition;
              vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5,
                                          aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
              vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uSharpness;
            varying vec2 vTexSamplingCoord;

            void main() {
              vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
              vec4 north = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -uTexelSize.y));
              vec4 south = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y));
              vec4 west = texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelSize.x, 0.0));
              vec4 east = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0));
              vec3 blur = (north.rgb + south.rgb + west.rgb + east.rgb) * 0.25;
              gl_FragColor = vec4(center.rgb + (center.rgb - blur) * uSharpness, center.a);
            }
        """
    }
}
