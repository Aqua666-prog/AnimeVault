package com.sergey.animevault.ui.player.audio

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.exoplayer.audio.ToFloatPcmAudioProcessor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Shared state for AnimeVault's in-player DSP. Configuration changes are lock-free and processing
 * code reads one immutable snapshot per audio buffer.
 */
internal class AnimeVaultAudioEngine(initialConfig: DspConfig) {
    private val configRef = AtomicReference(initialConfig.normalized())
    private val configVersion = AtomicLong(1L)
    private val metersRef = AtomicReference(DspMeters())
    private val clipCounter = AtomicLong(0L)

    internal val preProcessor = AnimeVaultPreDspAudioProcessor(this)
    internal val limiterProcessor = AnimeVaultLimiterAudioProcessor(this)

    fun config(): DspConfig = configRef.get()

    internal fun version(): Long = configVersion.get()

    fun setConfig(config: DspConfig) {
        configRef.set(config.normalized())
        configVersion.incrementAndGet()
    }

    fun updateConfig(block: (DspConfig) -> DspConfig) {
        while (true) {
            val current = configRef.get()
            val updated = block(current).normalized()
            if (configRef.compareAndSet(current, updated)) {
                configVersion.incrementAndGet()
                return
            }
        }
    }

    fun meters(): DspMeters = metersRef.get()

    internal fun publishInput(inputPeak: Float) {
        val old = metersRef.get()
        metersRef.set(old.copy(inputPeakDb = linearToDb(inputPeak)))
    }

    internal fun publishOutput(outputPeak: Float, rms: Float, limiterReductionDb: Float) {
        val old = metersRef.get()
        metersRef.set(
            old.copy(
                outputPeakDb = linearToDb(outputPeak),
                rmsDb = linearToDb(rms),
                limiterGainReductionDb = limiterReductionDb.coerceAtLeast(0f),
                hardClipCount = clipCounter.get(),
            ),
        )
    }

    internal fun registerHardClips(count: Long) {
        if (count > 0) clipCounter.addAndGet(count)
    }

    fun resetRuntimeState() {
        preProcessor.clearDspState()
        limiterProcessor.clearDspState()
        metersRef.set(DspMeters(hardClipCount = clipCounter.get()))
    }
}

/**
 * We keep Sonic inside the explicit chain so the limiter can remain after playback-speed
 * processing. Media-duration reporting mirrors Media3's DefaultAudioProcessorChain.
 */
internal class AnimeVaultAudioProcessorChain(
    engine: AnimeVaultAudioEngine,
) : AudioProcessorChain {
    private val toFloat = ToFloatPcmAudioProcessor()
    private val sonic = SonicAudioProcessor()
    private val toInt16 = ToInt16PcmAudioProcessor()
    private val processors: Array<AudioProcessor> = arrayOf(
        toFloat,
        engine.preProcessor,
        sonic,
        engine.limiterProcessor,
        toInt16,
    )

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long = if (sonic.isActive) {
        sonic.getMediaDuration(playoutDuration)
    } else {
        playoutDuration
    }

    override fun getSkippedOutputFrameCount(): Long = 0L
}

/** Pre-EQ and dynamics stage. The sample loop allocates no objects and calls no exp(). */
internal class AnimeVaultPreDspAudioProcessor(
    private val engine: AnimeVaultAudioEngine,
) : BaseAudioProcessor() {
    private var sampleRate = 48_000
    private var channelCount = 2
    private var appliedVersion = Long.MIN_VALUE
    private var currentPreamp = 1f
    private var targetPreamp = 1f
    private var currentMakeup = 1f
    private var targetMakeup = 1f
    private var gainStep = 0.005f

    private var leftEq = emptyArray<SmoothBiquad>()
    private var rightEq = emptyArray<SmoothBiquad>()
    private var activeEqIndices = IntArray(0)
    private val leftBass = SmoothBiquad()
    private val rightBass = SmoothBiquad()
    private var bassActive = false
    private val dynamics = ThreeBandDynamics()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT || inputAudioFormat.channelCount !in 1..2) {
            return AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        appliedVersion = Long.MIN_VALUE
        rebuild(force = true)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        rebuild(force = false)
        val config = engine.config()
        val frameBytes = channelCount * 4
        val frames = inputBuffer.remaining() / frameBytes
        val output = replaceOutputBuffer(frames * frameBytes)
        var inputPeak = 0f
        var frame = 0

        while (frame < frames) {
            var left = sanitize(inputBuffer.getFloat())
            var right = if (channelCount == 2) sanitize(inputBuffer.getFloat()) else left
            val samplePeak = max(abs(left), abs(right))
            if (samplePeak > inputPeak) inputPeak = samplePeak

            if (config.enabled) {
                currentPreamp += (targetPreamp - currentPreamp) * gainStep
                currentMakeup += (targetMakeup - currentMakeup) * gainStep
                left *= currentPreamp
                right *= currentPreamp

                var bandPos = 0
                while (bandPos < activeEqIndices.size) {
                    val index = activeEqIndices[bandPos]
                    left = leftEq[index].process(left)
                    right = rightEq[index].process(right)
                    bandPos++
                }
                if (bassActive) {
                    left = leftBass.process(left)
                    right = rightBass.process(right)
                }

                dynamics.process(left, right)
                left = sanitize(dynamics.outLeft * currentMakeup)
                right = sanitize(dynamics.outRight * currentMakeup)
            }

            output.putFloat(left)
            if (channelCount == 2) output.putFloat(right)
            frame++
        }

        val remainder = inputBuffer.remaining() % frameBytes
        if (remainder > 0) inputBuffer.position(inputBuffer.position() + remainder)
        output.flip()
        engine.publishInput(inputPeak)
    }

    override fun onFlush() {
        clearDspState()
        appliedVersion = Long.MIN_VALUE
        rebuild(force = true)
    }

    override fun onReset() {
        clearDspState()
        appliedVersion = Long.MIN_VALUE
    }

    internal fun clearDspState() {
        var i = 0
        while (i < leftEq.size) {
            leftEq[i].reset()
            rightEq[i].reset()
            i++
        }
        leftBass.reset()
        rightBass.reset()
        dynamics.reset()
    }

    private fun rebuild(force: Boolean) {
        val version = engine.version()
        if (!force && version == appliedVersion) return
        val config = engine.config()
        appliedVersion = version

        val smoothingSamples = if (force) 0 else (sampleRate * 0.020f).toInt().coerceAtLeast(1)
        if (leftEq.size != config.eqBands.size) {
            leftEq = Array(config.eqBands.size) { SmoothBiquad() }
            rightEq = Array(config.eqBands.size) { SmoothBiquad() }
        }

        val active = IntArray(config.eqBands.size)
        var activeCount = 0
        config.eqBands.forEachIndexed { index, band ->
            val coefficients = BiquadDesigner.design(band.type, sampleRate, band.frequencyHz, band.q, band.gainDb)
            leftEq[index].setCoefficients(coefficients, smoothingSamples)
            rightEq[index].setCoefficients(coefficients, smoothingSamples)
            if (abs(band.gainDb) > 0.01f) {
                active[activeCount++] = index
            }
        }
        activeEqIndices = active.copyOf(activeCount)

        val bassGain = config.bassPercent * 0.06f
        bassActive = config.enabled && abs(bassGain) > 0.01f
        val bassCoefficients = BiquadDesigner.design(DspFilterType.LOW_SHELF, sampleRate, 95f, 0.707f, bassGain)
        leftBass.setCoefficients(bassCoefficients, smoothingSamples)
        rightBass.setCoefficients(bassCoefficients, smoothingSamples)
        dynamics.configure(sampleRate, if (config.enabled) config.dynamicsAmount else 0f)

        val estimatedBoost = if (config.enabled && config.autoHeadroom) {
            estimateMaxEqBoostDb(sampleRate, config.eqBands, config.bassPercent)
        } else {
            0f
        }
        val headroom = if (estimatedBoost > 0.1f) estimatedBoost + 0.8f else 0f
        targetPreamp = dbToLinear(config.inputGainDb - headroom)
        targetMakeup = dbToLinear(config.loudnessPercent * 0.075f)
        if (force) {
            currentPreamp = targetPreamp
            currentMakeup = targetMakeup
        }
        // Approx. 20 ms one-pole interpolation, precomputed once per config change.
        gainStep = (1f - exp(-1.0 / (0.020 * sampleRate)).toFloat()).coerceIn(0.00001f, 1f)
    }

    private fun sanitize(value: Float): Float = if (value.isFinite()) value.coerceIn(-64f, 64f) else 0f
}

/**
 * Stereo-linked look-ahead limiter. Its real-time loop has no allocation and precomputes release
 * constants, ceiling and oversampling mode whenever configuration changes.
 */
internal class AnimeVaultLimiterAudioProcessor(
    private val engine: AnimeVaultAudioEngine,
) : BaseAudioProcessor() {
    private var sampleRate = 48_000
    private var channelCount = 2
    private var ring = FloatArray(2)
    private var ringFrames = 1
    private var writeFrame = 0
    private var bufferedFrames = 0
    private var gain = 1f
    private var l0 = 0f
    private var l1 = 0f
    private var l2 = 0f
    private var r0 = 0f
    private var r1 = 0f
    private var r2 = 0f
    private var appliedVersion = Long.MIN_VALUE

    private var limiterEnabled = false
    private var ceiling = dbToLinear(-1f)
    private var releaseCoeff = 0.999f
    private var oversampling = 2

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT || inputAudioFormat.channelCount !in 1..2) {
            return AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        appliedVersion = Long.MIN_VALUE
        rebuild(force = true)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        rebuild(force = false)
        val frameBytes = channelCount * 4
        val inputFrames = inputBuffer.remaining() / frameBytes
        val outputFrames = max(0, bufferedFrames + inputFrames - ringFrames)
        val output = replaceOutputBuffer(outputFrames * frameBytes)
        var outputPeak = 0f
        var sumSquares = 0.0
        var samplesWritten = 0
        var minGain = 1f
        var hardClips = 0L
        var frame = 0

        while (frame < inputFrames) {
            val left = sanitize(inputBuffer.getFloat())
            val right = if (channelCount == 2) sanitize(inputBuffer.getFloat()) else left

            var truePeak = maxOf(abs(left), abs(right))
            if (
                limiterEnabled && oversampling > 1 &&
                (truePeak >= ceiling * 0.70f || gain < 0.9995f)
            ) {
                truePeak = max(truePeak, estimateTruePeak(l0, l1, l2, left, r0, r1, r2, right, oversampling))
            }
            l0 = l1; l1 = l2; l2 = left
            r0 = r1; r1 = r2; r2 = right

            if (limiterEnabled) {
                val desiredGain = if (truePeak <= ceiling || truePeak <= 1e-9f) {
                    1f
                } else {
                    (ceiling / truePeak).coerceIn(0f, 1f)
                }
                gain = if (desiredGain < gain) {
                    desiredGain
                } else {
                    releaseCoeff * gain + (1f - releaseCoeff)
                }
                if (!gain.isFinite()) gain = 1f
                if (gain < minGain) minGain = gain
            } else {
                gain = 1f
            }

            val base = writeFrame * channelCount
            if (bufferedFrames == ringFrames) {
                var outLeft = ring[base] * gain
                var outRight = if (channelCount == 2) ring[base + 1] * gain else outLeft
                if (limiterEnabled) {
                    if (abs(outLeft) > ceiling * 1.001f) hardClips++
                    if (channelCount == 2 && abs(outRight) > ceiling * 1.001f) hardClips++
                    outLeft = outLeft.coerceIn(-ceiling, ceiling)
                    outRight = outRight.coerceIn(-ceiling, ceiling)
                }
                output.putFloat(outLeft)
                if (channelCount == 2) output.putFloat(outRight)

                val p = max(abs(outLeft), abs(outRight))
                if (p > outputPeak) outputPeak = p
                sumSquares += outLeft * outLeft
                samplesWritten++
                if (channelCount == 2) {
                    sumSquares += outRight * outRight
                    samplesWritten++
                }
            } else {
                bufferedFrames++
            }

            ring[base] = left
            if (channelCount == 2) ring[base + 1] = right
            writeFrame = (writeFrame + 1) % ringFrames
            frame++
        }

        val remainder = inputBuffer.remaining() % frameBytes
        if (remainder > 0) inputBuffer.position(inputBuffer.position() + remainder)
        output.flip()
        engine.registerHardClips(hardClips)
        val rms = if (samplesWritten > 0) sqrt(sumSquares / samplesWritten).toFloat() else 0f
        val reduction = if (minGain < 0.999999f) (-linearToDb(minGain)).coerceAtLeast(0f) else 0f
        engine.publishOutput(outputPeak, rms, reduction)
    }

    override fun onQueueEndOfStream() {
        if (bufferedFrames <= 0) return
        rebuild(force = false)
        val output = replaceOutputBuffer(bufferedFrames * channelCount * 4)
        var outputPeak = 0f
        var sumSquares = 0.0
        var samples = 0
        var hardClips = 0L
        val oldest = if (bufferedFrames == ringFrames) writeFrame else 0
        var offset = 0
        while (offset < bufferedFrames) {
            val frame = (oldest + offset) % ringFrames
            val base = frame * channelCount
            var left = ring[base] * gain
            var right = if (channelCount == 2) ring[base + 1] * gain else left
            if (limiterEnabled) {
                if (abs(left) > ceiling * 1.001f) hardClips++
                if (channelCount == 2 && abs(right) > ceiling * 1.001f) hardClips++
                left = left.coerceIn(-ceiling, ceiling)
                right = right.coerceIn(-ceiling, ceiling)
            }
            output.putFloat(left)
            if (channelCount == 2) output.putFloat(right)
            val p = max(abs(left), abs(right))
            if (p > outputPeak) outputPeak = p
            sumSquares += left * left
            samples++
            if (channelCount == 2) {
                sumSquares += right * right
                samples++
            }
            offset++
        }
        bufferedFrames = 0
        output.flip()
        engine.registerHardClips(hardClips)
        engine.publishOutput(
            outputPeak,
            if (samples > 0) sqrt(sumSquares / samples).toFloat() else 0f,
            if (gain < 0.999999f) (-linearToDb(gain)).coerceAtLeast(0f) else 0f,
        )
    }

    override fun onFlush() {
        clearDspState()
        appliedVersion = Long.MIN_VALUE
        rebuild(force = true)
    }

    override fun onReset() {
        clearDspState()
        appliedVersion = Long.MIN_VALUE
    }

    internal fun clearDspState() {
        ring.fill(0f)
        writeFrame = 0
        bufferedFrames = 0
        gain = 1f
        l0 = 0f; l1 = 0f; l2 = 0f
        r0 = 0f; r1 = 0f; r2 = 0f
    }

    private fun rebuild(force: Boolean) {
        val version = engine.version()
        if (!force && version == appliedVersion) return
        val config = engine.config()
        appliedVersion = version

        limiterEnabled = config.enabled
        ceiling = dbToLinear(config.limiter.ceilingDb)
        releaseCoeff = exp(-1.0 / (0.001 * config.limiter.releaseMs * sampleRate)).toFloat().coerceIn(0f, 0.9999999f)
        oversampling = config.limiter.oversampling.coerceIn(1, 4)

        val requestedFrames = (sampleRate * config.limiter.lookAheadMs / 1000f).toInt().coerceAtLeast(1)
        if (requestedFrames != ringFrames || ring.size != requestedFrames * channelCount) {
            ringFrames = requestedFrames
            ring = FloatArray(ringFrames * channelCount)
            clearDspState()
        }
    }

    private fun estimateTruePeak(
        l0: Float,
        l1: Float,
        l2: Float,
        l3: Float,
        r0: Float,
        r1: Float,
        r2: Float,
        r3: Float,
        factor: Int,
    ): Float {
        var peak = maxOf(abs(l1), abs(l2), abs(r1), abs(r2), abs(l3), abs(r3))
        var phase = 1
        while (phase < factor) {
            val t = phase.toFloat() / factor
            peak = max(peak, abs(catmullRom(l0, l1, l2, l3, t)))
            peak = max(peak, abs(catmullRom(r0, r1, r2, r3, t)))
            phase++
        }
        return peak
    }

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            2f * p1 +
                (-p0 + p2) * t +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                (-p0 + 3f * p1 - 3f * p2 + p3) * t3
            )
    }

    private fun sanitize(value: Float): Float = if (value.isFinite()) value.coerceIn(-64f, 64f) else 0f
}
