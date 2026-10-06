package com.sergey.animevault.ui.player.audio

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
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
import kotlin.math.min
import kotlin.math.sqrt

internal class AnimeVaultAudioEngine(initialConfig: DspConfig) {
    private val configRef = AtomicReference(initialConfig.normalized())
    private val metersRef = AtomicReference(DspMeters())
    private val clipCounter = AtomicLong(0L)

    internal val preProcessor = AnimeVaultPreDspAudioProcessor(this)
    internal val limiterProcessor = AnimeVaultLimiterAudioProcessor(this)

    fun config(): DspConfig = configRef.get()

    fun setConfig(config: DspConfig) {
        configRef.set(config.normalized())
    }

    fun updateConfig(block: (DspConfig) -> DspConfig) {
        while (true) {
            val current = configRef.get()
            val updated = block(current).normalized()
            if (configRef.compareAndSet(current, updated)) return
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

    internal fun registerHardClip() {
        clipCounter.incrementAndGet()
    }

    fun resetRuntimeState() {
        preProcessor.clearDspState()
        limiterProcessor.clearDspState()
        metersRef.set(DspMeters(hardClipCount = clipCounter.get()))
    }
}

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

internal class AnimeVaultPreDspAudioProcessor(
    private val engine: AnimeVaultAudioEngine,
) : BaseAudioProcessor() {
    private var sampleRate = 48_000
    private var channelCount = 2
    private var configuredSignature: Int = 0
    private var currentPreamp = 1f
    private var targetPreamp = 1f
    private var currentMakeup = 1f
    private var targetMakeup = 1f
    private var smoothCoeff = 0.995f

    private var leftEq = emptyArray<SmoothBiquad>()
    private var rightEq = emptyArray<SmoothBiquad>()
    private var leftBass = SmoothBiquad()
    private var rightBass = SmoothBiquad()
    private val dynamics = ThreeBandDynamics()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT || inputAudioFormat.channelCount !in 1..2) {
            return AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
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

        repeat(frames) {
            var left = sanitize(inputBuffer.getFloat())
            var right = if (channelCount == 2) sanitize(inputBuffer.getFloat()) else left
            inputPeak = max(inputPeak, max(abs(left), abs(right)))

            if (config.enabled) {
                currentPreamp += (targetPreamp - currentPreamp) * (1f - smoothCoeff)
                currentMakeup += (targetMakeup - currentMakeup) * (1f - smoothCoeff)
                left *= currentPreamp
                right *= currentPreamp

                leftEq.forEach { left = it.process(left) }
                rightEq.forEach { right = it.process(right) }
                left = leftBass.process(left)
                right = rightBass.process(right)

                val compressed = dynamics.process(left, right, config.dynamicsAmount)
                left = sanitize(compressed.first * currentMakeup)
                right = sanitize(compressed.second * currentMakeup)
            }

            output.putFloat(left)
            if (channelCount == 2) output.putFloat(right)
        }
        inputBuffer.position(inputBuffer.position() + inputBuffer.remaining() % frameBytes)
        output.flip()
        engine.publishInput(inputPeak)
    }

    override fun onFlush() {
        clearDspState()
        rebuild(force = true)
    }

    override fun onReset() {
        clearDspState()
        configuredSignature = 0
    }

    internal fun clearDspState() {
        leftEq.forEach(SmoothBiquad::reset)
        rightEq.forEach(SmoothBiquad::reset)
        leftBass.reset()
        rightBass.reset()
        dynamics.reset()
    }

    private fun rebuild(force: Boolean) {
        val config = engine.config()
        val signature = 31 * config.hashCode() + sampleRate
        if (!force && signature == configuredSignature) return
        configuredSignature = signature

        val smoothingSamples = if (force) 0 else (sampleRate * 0.025f).toInt().coerceAtLeast(1)
        if (leftEq.size != config.eqBands.size) {
            leftEq = Array(config.eqBands.size) { SmoothBiquad() }
            rightEq = Array(config.eqBands.size) { SmoothBiquad() }
        }
        config.eqBands.forEachIndexed { index, band ->
            val coefficients = BiquadDesigner.design(band.type, sampleRate, band.frequencyHz, band.q, band.gainDb)
            leftEq[index].setCoefficients(coefficients, smoothingSamples)
            rightEq[index].setCoefficients(coefficients, smoothingSamples)
        }
        val bassGain = config.bassPercent * 0.06f
        val bassCoefficients = BiquadDesigner.design(DspFilterType.LOW_SHELF, sampleRate, 95f, 0.707f, bassGain)
        leftBass.setCoefficients(bassCoefficients, smoothingSamples)
        rightBass.setCoefficients(bassCoefficients, smoothingSamples)
        dynamics.configure(sampleRate)

        val estimatedBoost = if (config.enabled && config.autoHeadroom) {
            estimateMaxEqBoostDb(sampleRate, config.eqBands, config.bassPercent)
        } else {
            0f
        }
        val headroom = if (estimatedBoost > 0.1f) estimatedBoost + 0.8f else 0f
        targetPreamp = dbToLinear(config.inputGainDb - headroom)
        val loudnessMakeupDb = config.loudnessPercent * 0.075f
        targetMakeup = dbToLinear(loudnessMakeupDb)
        if (force) {
            currentPreamp = targetPreamp
            currentMakeup = targetMakeup
        }
        smoothCoeff = exp(-1.0 / (0.025 * sampleRate)).toFloat().coerceIn(0f, 0.99999f)
    }

    private fun sanitize(value: Float): Float = if (value.isFinite()) value.coerceIn(-64f, 64f) else 0f
}

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
    private var configuredSignature = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT || inputAudioFormat.channelCount !in 1..2) {
            return AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        rebuild(force = true)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        rebuild(force = false)
        val config = engine.config()
        val frameBytes = channelCount * 4
        val inputFrames = inputBuffer.remaining() / frameBytes
        val outputFrames = max(0, bufferedFrames + inputFrames - ringFrames)
        val output = replaceOutputBuffer(outputFrames * frameBytes)
        var outputPeak = 0f
        var sumSquares = 0.0
        var samplesWritten = 0
        var maxReduction = 0f

        repeat(inputFrames) {
            val left = sanitize(inputBuffer.getFloat())
            val right = if (channelCount == 2) sanitize(inputBuffer.getFloat()) else left
            val truePeak = estimateTruePeak(l0, l1, l2, left, r0, r1, r2, right, config.limiter.oversampling)
            l0 = l1
            l1 = l2
            l2 = left
            r0 = r1
            r1 = r2
            r2 = right

            val ceiling = dbToLinear(config.limiter.ceilingDb)
            val desiredGain = if (!config.enabled || truePeak <= ceiling || truePeak <= 1e-9f) 1f else (ceiling / truePeak).coerceIn(0f, 1f)
            if (desiredGain < gain) {
                gain = desiredGain
            } else {
                val releaseCoeff = exp(-1.0 / (0.001 * config.limiter.releaseMs * sampleRate)).toFloat()
                gain = releaseCoeff * gain + (1f - releaseCoeff)
            }
            if (!gain.isFinite()) gain = 1f
            maxReduction = max(maxReduction, (-linearToDb(gain.coerceAtMost(1f))).coerceAtLeast(0f))

            val base = writeFrame * channelCount
            if (bufferedFrames == ringFrames) {
                val outLeft = finishSample(ring[base] * gain, ceiling, config.enabled)
                val outRight = if (channelCount == 2) finishSample(ring[base + 1] * gain, ceiling, config.enabled) else outLeft
                output.putFloat(outLeft)
                if (channelCount == 2) output.putFloat(outRight)
                outputPeak = max(outputPeak, max(abs(outLeft), abs(outRight)))
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
        }
        inputBuffer.position(inputBuffer.position() + inputBuffer.remaining() % frameBytes)
        output.flip()
        val rms = if (samplesWritten > 0) sqrt(sumSquares / samplesWritten).toFloat() else 0f
        engine.publishOutput(outputPeak, rms, maxReduction)
    }

    override fun onQueueEndOfStream() {
        if (bufferedFrames <= 0) return
        val config = engine.config()
        val output = replaceOutputBuffer(bufferedFrames * channelCount * 4)
        val ceiling = dbToLinear(config.limiter.ceilingDb)
        var outputPeak = 0f
        var sumSquares = 0.0
        var samples = 0
        val oldest = if (bufferedFrames == ringFrames) writeFrame else 0
        repeat(bufferedFrames) { offset ->
            val frame = (oldest + offset) % ringFrames
            val base = frame * channelCount
            val left = finishSample(ring[base] * gain, ceiling, config.enabled)
            val right = if (channelCount == 2) finishSample(ring[base + 1] * gain, ceiling, config.enabled) else left
            output.putFloat(left)
            if (channelCount == 2) output.putFloat(right)
            outputPeak = max(outputPeak, max(abs(left), abs(right)))
            sumSquares += left * left
            samples++
            if (channelCount == 2) {
                sumSquares += right * right
                samples++
            }
        }
        bufferedFrames = 0
        output.flip()
        engine.publishOutput(outputPeak, if (samples > 0) sqrt(sumSquares / samples).toFloat() else 0f, (-linearToDb(gain.coerceAtMost(1f))).coerceAtLeast(0f))
    }

    override fun onFlush() {
        clearDspState()
        rebuild(force = true)
    }

    override fun onReset() {
        clearDspState()
        configuredSignature = 0
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
        val config = engine.config()
        val signature = 31 * config.limiter.hashCode() + sampleRate * 7 + channelCount
        if (!force && signature == configuredSignature) return
        configuredSignature = signature
        val requestedFrames = (sampleRate * config.limiter.lookAheadMs / 1000f).toInt().coerceAtLeast(1)
        if (requestedFrames != ringFrames || ring.size != requestedFrames * channelCount) {
            ringFrames = requestedFrames
            ring = FloatArray(ringFrames * channelCount)
            clearDspState()
        }
    }

    private fun estimateTruePeak(
        l0: Float, l1: Float, l2: Float, l3: Float,
        r0: Float, r1: Float, r2: Float, r3: Float,
        oversampling: Int,
    ): Float {
        var peak = maxOf(abs(l1), abs(l2), abs(r1), abs(r2), abs(l3), abs(r3))
        val factor = oversampling.coerceIn(1, 4)
        if (factor <= 1) return peak
        // Four-point Catmull-Rom interpolation estimates the segment between the two middle samples.
        // Unlike linear interpolation it can expose inter-sample overshoot. One-sample detector delay
        // is far smaller than the 5-6 ms look-ahead buffer.
        for (phase in 1 until factor) {
            val t = phase.toFloat() / factor
            peak = max(peak, abs(catmullRom(l0, l1, l2, l3, t)))
            peak = max(peak, abs(catmullRom(r0, r1, r2, r3, t)))
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

    private fun finishSample(value: Float, ceiling: Float, limiterEnabled: Boolean): Float {
        if (!value.isFinite()) return 0f
        if (!limiterEnabled) return value.coerceIn(-1f, 1f)
        if (abs(value) > ceiling * 1.001f) engine.registerHardClip()
        return value.coerceIn(-ceiling, ceiling)
    }

    private fun sanitize(value: Float): Float = if (value.isFinite()) value.coerceIn(-64f, 64f) else 0f
}
