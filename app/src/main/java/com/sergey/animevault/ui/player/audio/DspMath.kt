package com.sergey.animevault.ui.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class BiquadCoefficients(
    val b0: Double = 1.0,
    val b1: Double = 0.0,
    val b2: Double = 0.0,
    val a1: Double = 0.0,
    val a2: Double = 0.0,
)

internal object BiquadDesigner {
    fun design(
        type: DspFilterType,
        sampleRate: Int,
        frequencyHz: Float,
        q: Float,
        gainDb: Float,
    ): BiquadCoefficients {
        if (sampleRate <= 0) return BiquadCoefficients()
        val nyquist = sampleRate * 0.5
        val f = frequencyHz.toDouble().coerceIn(10.0, (nyquist * 0.95).coerceAtLeast(10.0))
        val safeQ = q.toDouble().coerceIn(0.1, 20.0)
        val a = 10.0.pow(gainDb.toDouble() / 40.0)
        val w0 = 2.0 * PI * f / sampleRate.toDouble()
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * safeQ)

        val raw = when (type) {
            DspFilterType.PEAK -> {
                val b0 = 1.0 + alpha * a
                val b1 = -2.0 * cw
                val b2 = 1.0 - alpha * a
                val a0 = 1.0 + alpha / a
                val a1 = -2.0 * cw
                val a2 = 1.0 - alpha / a
                doubleArrayOf(b0, b1, b2, a0, a1, a2)
            }
            DspFilterType.LOW_SHELF -> {
                val shelfQ = safeQ.coerceIn(0.3, 2.0)
                val alphaShelf = sw / 2.0 * sqrt((a + 1.0 / a) * (1.0 / shelfQ - 1.0) + 2.0)
                val beta = 2.0 * sqrt(a) * alphaShelf
                val b0 = a * ((a + 1.0) - (a - 1.0) * cw + beta)
                val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cw)
                val b2 = a * ((a + 1.0) - (a - 1.0) * cw - beta)
                val a0 = (a + 1.0) + (a - 1.0) * cw + beta
                val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cw)
                val a2 = (a + 1.0) + (a - 1.0) * cw - beta
                doubleArrayOf(b0, b1, b2, a0, a1, a2)
            }
            DspFilterType.HIGH_SHELF -> {
                val shelfQ = safeQ.coerceIn(0.3, 2.0)
                val alphaShelf = sw / 2.0 * sqrt((a + 1.0 / a) * (1.0 / shelfQ - 1.0) + 2.0)
                val beta = 2.0 * sqrt(a) * alphaShelf
                val b0 = a * ((a + 1.0) + (a - 1.0) * cw + beta)
                val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw)
                val b2 = a * ((a + 1.0) + (a - 1.0) * cw - beta)
                val a0 = (a + 1.0) - (a - 1.0) * cw + beta
                val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw)
                val a2 = (a + 1.0) - (a - 1.0) * cw - beta
                doubleArrayOf(b0, b1, b2, a0, a1, a2)
            }
        }
        val a0 = raw[3]
        if (!a0.isFinite() || abs(a0) < 1e-12) return BiquadCoefficients()
        return BiquadCoefficients(
            b0 = raw[0] / a0,
            b1 = raw[1] / a0,
            b2 = raw[2] / a0,
            a1 = raw[4] / a0,
            a2 = raw[5] / a0,
        )
    }

    fun lowPass(sampleRate: Int, frequencyHz: Float, q: Float = 0.70710678f): BiquadCoefficients =
        lowHigh(sampleRate, frequencyHz, q, highPass = false)

    fun highPass(sampleRate: Int, frequencyHz: Float, q: Float = 0.70710678f): BiquadCoefficients =
        lowHigh(sampleRate, frequencyHz, q, highPass = true)

    private fun lowHigh(sampleRate: Int, frequencyHz: Float, q: Float, highPass: Boolean): BiquadCoefficients {
        val f = frequencyHz.toDouble().coerceIn(10.0, sampleRate * 0.45)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * q.coerceAtLeast(0.1f))
        val b0 = if (highPass) (1.0 + cw) / 2.0 else (1.0 - cw) / 2.0
        val b1 = if (highPass) -(1.0 + cw) else 1.0 - cw
        val b2 = b0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cw
        val a2 = 1.0 - alpha
        return BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}

/**
 * Direct-form-II biquad with zero heap allocation in process(). Coefficient ramps are precomputed
 * when a preset/slider changes; the audio thread only performs primitive arithmetic.
 */
internal class SmoothBiquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var targetB0 = 1.0
    private var targetB1 = 0.0
    private var targetB2 = 0.0
    private var targetA1 = 0.0
    private var targetA2 = 0.0

    private var stepB0 = 0.0
    private var stepB1 = 0.0
    private var stepB2 = 0.0
    private var stepA1 = 0.0
    private var stepA2 = 0.0
    private var remaining = 0
    private var bypass = true

    private var z1 = 0.0
    private var z2 = 0.0

    fun setCoefficients(value: BiquadCoefficients, smoothingSamples: Int) {
        if (!allFinite(value)) return
        targetB0 = value.b0
        targetB1 = value.b1
        targetB2 = value.b2
        targetA1 = value.a1
        targetA2 = value.a2

        if (smoothingSamples <= 0) {
            b0 = targetB0
            b1 = targetB1
            b2 = targetB2
            a1 = targetA1
            a2 = targetA2
            remaining = 0
            bypass = isIdentity()
            if (bypass) reset()
            return
        }

        remaining = smoothingSamples
        val inv = 1.0 / smoothingSamples.toDouble()
        stepB0 = (targetB0 - b0) * inv
        stepB1 = (targetB1 - b1) * inv
        stepB2 = (targetB2 - b2) * inv
        stepA1 = (targetA1 - a1) * inv
        stepA2 = (targetA2 - a2) * inv
        bypass = false
    }

    fun process(input: Float): Float {
        if (remaining > 0) {
            b0 += stepB0
            b1 += stepB1
            b2 += stepB2
            a1 += stepA1
            a2 += stepA2
            remaining--
            if (remaining == 0) {
                b0 = targetB0
                b1 = targetB1
                b2 = targetB2
                a1 = targetA1
                a2 = targetA2
                if (isIdentity() && abs(z1) < 1e-12 && abs(z2) < 1e-12) bypass = true
            }
        }
        if (bypass) return input

        val x = if (input.isFinite()) input.toDouble() else 0.0
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        if (!y.isFinite() || !z1.isFinite() || !z2.isFinite()) {
            reset()
            return 0f
        }
        return y.toFloat()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    private fun isIdentity(): Boolean =
        abs(b0 - 1.0) < 1e-12 && abs(b1) < 1e-12 && abs(b2) < 1e-12 && abs(a1) < 1e-12 && abs(a2) < 1e-12

    private fun allFinite(v: BiquadCoefficients): Boolean =
        v.b0.isFinite() && v.b1.isFinite() && v.b2.isFinite() && v.a1.isFinite() && v.a2.isFinite()
}

internal fun estimateMaxEqBoostDb(sampleRate: Int, bands: List<DspEqBand>, bassPercent: Float): Float {
    if (sampleRate <= 0) return 0f
    val coeffs = buildList {
        bands.forEach { add(BiquadDesigner.design(it.type, sampleRate, it.frequencyHz, it.q, it.gainDb)) }
        val bassGain = bassPercent.coerceIn(0f, 100f) * 0.06f
        if (bassGain > 0.01f) add(BiquadDesigner.design(DspFilterType.LOW_SHELF, sampleRate, 95f, 0.707f, bassGain))
    }
    var maxDb = 0.0
    val minF = 20.0
    val maxF = minOf(20_000.0, sampleRate * 0.45)
    repeat(96) { index ->
        val ratio = index / 95.0
        val f = minF * (maxF / minF).pow(ratio)
        val w = 2.0 * PI * f / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2.0 * w)
        val s2 = sin(2.0 * w)
        var sumDb = 0.0
        coeffs.forEach { c ->
            val nr = c.b0 + c.b1 * c1 + c.b2 * c2
            val ni = -c.b1 * s1 - c.b2 * s2
            val dr = 1.0 + c.a1 * c1 + c.a2 * c2
            val di = -c.a1 * s1 - c.a2 * s2
            val numerator = nr * nr + ni * ni
            val denominator = (dr * dr + di * di).coerceAtLeast(1e-18)
            val mag = sqrt(numerator / denominator).coerceAtLeast(1e-9)
            sumDb += 20.0 * ln(mag) / ln(10.0)
        }
        if (sumDb.isFinite()) maxDb = maxOf(maxDb, sumDb)
    }
    return maxDb.toFloat().coerceAtLeast(0f)
}

/** Stereo-linked compressor. process() performs no allocation, log(), pow() or exp() calls. */
internal class StereoLinkedCompressor {
    private var sampleRate = 48_000
    private var envelope = 0f
    private var gain = 1f

    private var thresholdLinear = dbToLinear(-18f)
    private var kneeStart = thresholdLinear
    private var kneeEnd = thresholdLinear
    private var ratio = 2f
    private var makeupLinear = 1f
    private var detectorAttackCoeff = 0f
    private var detectorReleaseCoeff = 0f
    private var gainAttackCoeff = 0f
    private var gainReleaseCoeff = 0f

    var outLeft: Float = 0f
        private set
    var outRight: Float = 0f
        private set

    fun configure(sampleRate: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(8_000)
    }

    fun setParameters(
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
        kneeDb: Float,
        makeupDb: Float,
    ) {
        thresholdLinear = dbToLinear(thresholdDb)
        val halfKnee = kneeDb.coerceAtLeast(0.001f) * 0.5f
        kneeStart = thresholdLinear * dbToLinear(-halfKnee)
        kneeEnd = thresholdLinear * dbToLinear(halfKnee)
        this.ratio = ratio.coerceAtLeast(1f)
        makeupLinear = dbToLinear(makeupDb)
        detectorAttackCoeff = timeCoefficient(attackMs)
        detectorReleaseCoeff = timeCoefficient(releaseMs)
        gainAttackCoeff = timeCoefficient(2f)
        gainReleaseCoeff = detectorReleaseCoeff
    }

    fun process(left: Float, right: Float) {
        val detector = maxOf(abs(left), abs(right)).coerceAtMost(64f)
        val detectorCoeff = if (detector > envelope) detectorAttackCoeff else detectorReleaseCoeff
        envelope = detectorCoeff * envelope + (1f - detectorCoeff) * detector

        var compressionGain = 1f
        if (envelope > kneeStart && envelope > 1e-9f) {
            val over = (envelope - thresholdLinear).coerceAtLeast(0f)
            val compressedLevel = thresholdLinear + over / ratio
            val hardGain = (compressedLevel / envelope).coerceIn(0f, 1f)
            compressionGain = if (envelope >= kneeEnd || kneeEnd <= kneeStart + 1e-9f) {
                hardGain
            } else {
                val t = ((envelope - kneeStart) / (kneeEnd - kneeStart)).coerceIn(0f, 1f)
                val smooth = t * t * (3f - 2f * t)
                1f + (hardGain - 1f) * smooth
            }
        }

        val targetGain = compressionGain * makeupLinear
        val smoothing = if (targetGain < gain) gainAttackCoeff else gainReleaseCoeff
        gain = smoothing * gain + (1f - smoothing) * targetGain
        if (!gain.isFinite()) gain = 1f

        outLeft = left * gain
        outRight = right * gain
    }

    fun gainReductionDb(): Float = (-linearToDb(gain.coerceAtMost(1f))).coerceAtLeast(0f)

    fun reset() {
        envelope = 0f
        gain = 1f
        outLeft = 0f
        outRight = 0f
    }

    private fun timeCoefficient(ms: Float): Float =
        exp(-1.0 / (0.001 * ms.coerceAtLeast(0.1f) * sampleRate)).toFloat()
}

internal class LinkwitzRileySplit {
    private val low1 = SmoothBiquad()
    private val low2 = SmoothBiquad()
    private val high1 = SmoothBiquad()
    private val high2 = SmoothBiquad()

    var low: Float = 0f
        private set
    var high: Float = 0f
        private set

    fun configure(sampleRate: Int, frequencyHz: Float) {
        val lp = BiquadDesigner.lowPass(sampleRate, frequencyHz)
        val hp = BiquadDesigner.highPass(sampleRate, frequencyHz)
        low1.setCoefficients(lp, 0)
        low2.setCoefficients(lp, 0)
        high1.setCoefficients(hp, 0)
        high2.setCoefficients(hp, 0)
    }

    fun process(input: Float) {
        low = low2.process(low1.process(input))
        high = high2.process(high1.process(input))
    }

    fun reset() {
        low1.reset(); low2.reset(); high1.reset(); high2.reset()
        low = 0f; high = 0f
    }
}

/**
 * Three-band dynamics with a zero-allocation sample path. Expensive time constants are calculated
 * only when sample rate or loudness amount changes.
 */
internal class ThreeBandDynamics {
    private val splitLowL = LinkwitzRileySplit()
    private val splitLowR = LinkwitzRileySplit()
    private val splitHighL = LinkwitzRileySplit()
    private val splitHighR = LinkwitzRileySplit()
    private val lowComp = StereoLinkedCompressor()
    private val midComp = StereoLinkedCompressor()
    private val highComp = StereoLinkedCompressor()

    private var sampleRate = 0
    private var configuredAmount = -1f
    private var enabled = false

    var outLeft: Float = 0f
        private set
    var outRight: Float = 0f
        private set

    fun configure(sampleRate: Int, amount: Float) {
        val safeRate = sampleRate.coerceAtLeast(8_000)
        val a = amount.coerceIn(0f, 1f)
        if (this.sampleRate != safeRate) {
            this.sampleRate = safeRate
            splitLowL.configure(safeRate, 130f)
            splitLowR.configure(safeRate, 130f)
            splitHighL.configure(safeRate, 2_800f)
            splitHighR.configure(safeRate, 2_800f)
            lowComp.configure(safeRate)
            midComp.configure(safeRate)
            highComp.configure(safeRate)
            configuredAmount = -1f
        }
        if (abs(configuredAmount - a) < 0.0001f) return
        configuredAmount = a
        enabled = a > 0.001f
        if (!enabled) return

        val ratio = 1f + 3.2f * a
        val knee = 4f + 4f * a
        lowComp.setParameters(-16f - 13f * a, ratio, 18f, 120f, knee, 0.4f * a)
        midComp.setParameters(-19f - 12f * a, ratio, 8f, 90f, knee, 0.8f * a)
        highComp.setParameters(-17f - 10f * a, 1f + 2.6f * a, 4f, 70f, knee, 0.4f * a)
    }

    fun process(left: Float, right: Float) {
        if (!enabled) {
            outLeft = left
            outRight = right
            return
        }

        splitLowL.process(left)
        splitLowR.process(right)
        splitHighL.process(splitLowL.high)
        splitHighR.process(splitLowR.high)

        lowComp.process(splitLowL.low, splitLowR.low)
        midComp.process(splitHighL.low, splitHighR.low)
        highComp.process(splitHighL.high, splitHighR.high)

        outLeft = lowComp.outLeft + midComp.outLeft + highComp.outLeft
        outRight = lowComp.outRight + midComp.outRight + highComp.outRight
    }

    fun maxGainReductionDb(): Float = maxOf(
        lowComp.gainReductionDb(),
        midComp.gainReductionDb(),
        highComp.gainReductionDb(),
    )

    fun reset() {
        splitLowL.reset(); splitLowR.reset(); splitHighL.reset(); splitHighR.reset()
        lowComp.reset(); midComp.reset(); highComp.reset()
        outLeft = 0f; outRight = 0f
    }
}
