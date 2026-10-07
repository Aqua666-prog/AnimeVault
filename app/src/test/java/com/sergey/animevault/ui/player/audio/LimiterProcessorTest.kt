package com.sergey.animevault.ui.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Test

class LimiterProcessorTest {
    @Suppress("DEPRECATION")
    @Test
    fun `lookahead limiter keeps float samples under ceiling and preserves frame count`() {
        val config = DspPresets.config("MAX").copy(
            limiter = DspLimiterSettings(ceilingDb = -1f, lookAheadMs = 5f, releaseMs = 100f, oversampling = 4),
        )
        val engine = AnimeVaultAudioEngine(config)
        val limiter = engine.limiterProcessor
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)
        limiter.configure(format)
        limiter.flush()

        val frames = 2_000
        val input = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { frame ->
            val sample = if (frame % 97 == 0) 1.4f else 0.85f
            input.putFloat(sample)
            input.putFloat(-sample)
        }
        input.flip()

        limiter.queueInput(input)
        val first = limiter.output
        val values = ArrayList<Float>(frames * 2)
        while (first.hasRemaining()) values += first.getFloat()
        limiter.queueEndOfStream()
        val tail = limiter.output
        while (tail.hasRemaining()) values += tail.getFloat()

        assertThat(values).hasSize(frames * 2)
        val ceiling = dbToLinear(-1f)
        assertThat(values.maxOf { abs(it) }).isAtMost(ceiling + 1e-4f)
        assertThat(engine.meters().limiterGainReductionDb).isGreaterThan(0f)
    }
}
