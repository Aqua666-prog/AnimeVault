package com.sergey.animevault.ui.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Test

class LimiterProcessorTest {
    @Test
    fun `releasing old sink does not discard new sink lookahead audio`() {
        val engine = AnimeVaultAudioEngine(DspConfig(enabled = false))
        val oldChain = AnimeVaultAudioProcessorChain(engine)
        val newChain = AnimeVaultAudioProcessorChain(engine)
        val oldLimiter = oldChain.getAudioProcessors().filterIsInstance<AnimeVaultLimiterAudioProcessor>().single()
        val newLimiter = newChain.getAudioProcessors().filterIsInstance<AnimeVaultLimiterAudioProcessor>().single()
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)
        oldLimiter.configure(format)
        oldLimiter.flush()
        newLimiter.configure(format)
        newLimiter.flush()

        newLimiter.queueInput(stereoBuffer(200, 0.25f))
        assertThat(newLimiter.output.remaining()).isEqualTo(0)
        oldChain.getAudioProcessors().forEach { it.reset() }
        newLimiter.queueInput(stereoBuffer(100, 0.25f))
        val first = readFloats(newLimiter.output)
        newLimiter.queueEndOfStream()
        val tail = readFloats(newLimiter.output)

        assertThat(first).hasSize(60 * 2)
        assertThat(first + tail).hasSize(300 * 2)
        assertThat((first + tail).all { it == 0.25f }).isTrue()
    }

    @Test
    fun `changing preset during playback preserves buffered frames`() {
        val engine = AnimeVaultAudioEngine(DspPresets.config("MAX"))
        val limiter = AnimeVaultLimiterAudioProcessor(engine)
        limiter.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        limiter.flush()
        limiter.queueInput(stereoBuffer(200, 0.25f))
        assertThat(limiter.output.remaining()).isEqualTo(0)
        engine.setConfig(DspPresets.config("OFF"))
        limiter.queueInput(stereoBuffer(100, 0.25f))
        val first = readFloats(limiter.output)
        engine.setConfig(DspPresets.config("DIALOGUE"))
        limiter.queueEndOfStream()
        val tail = readFloats(limiter.output)

        assertThat(first + tail).hasSize(300 * 2)
        assertThat((first + tail).all { it == 0.25f }).isTrue()
    }

    @Test
    fun `seek flush discards previous lookahead samples`() {
        val engine = AnimeVaultAudioEngine(DspConfig(enabled = false))
        val limiter = AnimeVaultLimiterAudioProcessor(engine)
        limiter.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        limiter.flush()
        limiter.queueInput(stereoBuffer(200, 0.75f))
        limiter.output
        limiter.flush()
        limiter.queueInput(stereoBuffer(300, -0.25f))
        val first = readFloats(limiter.output)
        limiter.queueEndOfStream()
        val tail = readFloats(limiter.output)

        assertThat(first + tail).hasSize(300 * 2)
        assertThat((first + tail).all { it == -0.25f }).isTrue()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `lookahead limiter keeps float samples under ceiling and preserves frame count`() {
        val config = DspPresets.config("MAX").copy(
            limiter = DspLimiterSettings(ceilingDb = -1f, lookAheadMs = 5f, releaseMs = 100f, oversampling = 4),
        )
        val engine = AnimeVaultAudioEngine(config)
        val limiter = AnimeVaultLimiterAudioProcessor(engine)
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

    private fun stereoBuffer(frames: Int, value: Float): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(frames * 2) { putFloat(value) }
            flip()
        }

    private fun readFloats(buffer: ByteBuffer): List<Float> = buildList {
        while (buffer.hasRemaining()) add(buffer.getFloat())
    }
}
