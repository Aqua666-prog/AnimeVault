package com.sergey.animevault.ui.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.Effect
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import kotlin.math.roundToInt

/**
 * Lightweight Anime4K prototype for real-time Media3 playback.
 *
 * The luminance residual, threshold curve and local min/max clamp are derived from
 * Anime4K_Deblur_DoG.glsl (Anime4K, MIT, bloc97, pinned upstream commit
 * 7684e9586f8dcc738af08a1cdceb024cc184f426). The original multi-pass 7-tap
 * Gaussian is deliberately reduced to a single-pass 3x3 kernel for mobile GPUs.
 * Frames below 1080p are bilinearly enlarged by the GL pipeline up to 2x within a
 * 1920x1080 envelope; 1080p and larger inputs are enhanced at native resolution.
 */
internal class Anime4kLightEffect(
    private val strength: Float = DEFAULT_STRENGTH,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        // The prototype is tuned for SDR BT.709-like anime sources. Keep HDR untouched rather
        // than applying a luma curve in the wrong transfer/color space.
        return if (useHdr) PassthroughShaderProgram() else Anime4kLightShaderProgram(strength)
    }

    companion object {
        const val DEFAULT_STRENGTH = 0.55f
    }
}

internal fun anime4kVideoEffects(enabled: Boolean): List<Effect> =
    if (enabled) listOf(Anime4kLightEffect()) else emptyList()

internal fun PlaybackException.isAnime4kVideoProcessingFailure(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED

internal fun anime4kOutputSize(inputWidth: Int, inputHeight: Int): Size {
    require(inputWidth > 0 && inputHeight > 0)
    val longEdge = maxOf(inputWidth, inputHeight).toFloat()
    val shortEdge = minOf(inputWidth, inputHeight).toFloat()
    val scale = minOf(
        2f,
        MAX_LONG_EDGE / longEdge,
        MAX_SHORT_EDGE / shortEdge,
    ).coerceAtLeast(1f)
    return Size(
        evenDimension((inputWidth * scale).roundToInt()),
        evenDimension((inputHeight * scale).roundToInt()),
    )
}

private fun evenDimension(value: Int): Int = value.coerceAtLeast(2).let { it - (it and 1) }

private class Anime4kLightShaderProgram(
    strength: Float,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents = */ false,
    /* texturePoolCapacity = */ 1,
) {
    private val glProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (error: GlUtil.GlException) {
        throw VideoFrameProcessingException(error)
    }

    init {
        try {
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            val identity = GlUtil.create4x4IdentityMatrix()
            glProgram.setFloatsUniform("uTransformationMatrix", identity)
            glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
            glProgram.setFloatUniform("uStrength", strength.coerceIn(0f, 1f))
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val output = anime4kOutputSize(inputWidth, inputHeight)
        try {
            glProgram.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(1f / inputWidth, 1f / inputHeight),
            )
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error)
        }
        return output
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error)
        }
    }
}

private const val MAX_LONG_EDGE = 1920f
private const val MAX_SHORT_EDGE = 1080f

private const val VERTEX_SHADER = """
#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
    gl_Position = uTransformationMatrix * aFramePosition;
    vec4 texturePosition = vec4(
        aFramePosition.x * 0.5 + 0.5,
        aFramePosition.y * 0.5 + 0.5,
        0.0,
        1.0
    );
    vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

private const val FRAGMENT_SHADER = """
#version 100
precision mediump float;
varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uStrength;

float a4kLuma(vec3 rgb) {
    return dot(rgb, vec3(0.299, 0.587, 0.114));
}

float sampleLuma(vec2 offset) {
    return a4kLuma(texture2D(uTexSampler, vTexSamplingCoord + offset * uTexelSize).rgb);
}

void main() {
    vec4 source = texture2D(uTexSampler, vTexSamplingCoord);
    float c = a4kLuma(source.rgb);

    float n  = sampleLuma(vec2( 0.0, -1.0));
    float s  = sampleLuma(vec2( 0.0,  1.0));
    float w  = sampleLuma(vec2(-1.0,  0.0));
    float e  = sampleLuma(vec2( 1.0,  0.0));
    float nw = sampleLuma(vec2(-1.0, -1.0));
    float ne = sampleLuma(vec2( 1.0, -1.0));
    float sw = sampleLuma(vec2(-1.0,  1.0));
    float se = sampleLuma(vec2( 1.0,  1.0));

    // Mobile single-pass approximation of Anime4K's separable Gaussian stage.
    float blurred = (4.0 * c + 2.0 * (n + s + w + e) + nw + ne + sw + se) / 16.0;
    float residual = (c - blurred) * uStrength;

    const float blurThreshold = 0.10;
    const float noiseThreshold = 0.001;
    const float blurCurve = 0.60;
    float thresholdRange = blurThreshold - noiseThreshold;
    float magnitude = abs(residual);
    float adjusted;
    if (magnitude > noiseThreshold) {
        float normalized = clamp((magnitude - noiseThreshold) / thresholdRange, 0.0, 1.0);
        float curved = pow(normalized, blurCurve) * thresholdRange + noiseThreshold;
        adjusted = curved * sign(residual);
    } else {
        adjusted = residual;
    }

    float localMin = min(c, min(min(n, s), min(min(w, e), min(min(nw, ne), min(sw, se)))));
    float localMax = max(c, max(max(n, s), max(max(w, e), max(max(nw, ne), max(sw, se)))));
    float targetLuma = clamp(c + adjusted, localMin, localMax);
    float delta = targetLuma - c;

    gl_FragColor = vec4(clamp(source.rgb + vec3(delta), 0.0, 1.0), source.a);
}
"""
