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

internal class SmoothBiquad {
    private var c = BiquadCoefficients()
    private var target = c
    private var remaining = 0
    private var z1 = 0.0
    private var z2 = 0.0

    fun setCoefficients(value: BiquadCoefficients, smoothingSamples: Int) {
        if (!allFinite(value)) return
        target = value
        if (smoothingSamples <= 0 || !allFinite(c)) {
            c = value
            remaining = 0
        } else {
            remaining = smoothingSamples
        }
    }

    fun process(input: Float): Float {
        if (remaining > 0) {
            val t = 1.0 / remaining.toDouble()
            c = BiquadCoefficients(
                b0 = c.b0 + (target.b0 - c.b0) * t,
                b1 = c.b1 + (target.b1 - c.b1) * t,
                b2 = c.b2 + (target.b2 - c.b2) * t,
                a1 = c.a1 + (target.a1 - c.a1) * t,
                a2 = c.a2 + (target.a2 - c.a2) * t,
            )
            remaining--
        }
        val x = if (input.isFinite()) input.toDouble() else 0.0
        val y = c.b0 * x + z1
        z1 = c.b1 * x - c.a1 * y + z2
        z2 = c.b2 * x - c.a2 * y
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

internal class StereoLinkedCompressor {
    private var sampleRate = 48_000
    private var envelope = 0f
    private var gain = 1f

    fun configure(sampleRate: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(8_000)
    }

    fun process(left: Float, right: Float, thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, kneeDb: Float, makeupDb: Float): Pair<Float, Float> {
        val detector = maxOf(abs(left), abs(right)).coerceAtMost(64f)
        val attack = coefficient(attackMs)
        val release = coefficient(releaseMs)
        envelope = if (detector > envelope) {
            attack * envelope + (1f - attack) * detector
        } else {
            release * envelope + (1f - release) * detector
        }
        val levelDb = linearToDb(envelope)
        val reductionDb = softKneeGainDb(levelDb, thresholdDb, ratio, kneeDb)
        val targetGain = dbToLinear(reductionDb + makeupDb)
        // Gain smoothing is intentionally a little slower than the detector to suppress zippering.
        val smoothing = if (targetGain < gain) coefficient(2f) else coefficient(releaseMs)
        gain = smoothing * gain + (1f - smoothing) * targetGain
        if (!gain.isFinite()) gain = 1f
        return (left * gain) to (right * gain)
    }

    fun gainReductionDb(): Float = (-linearToDb(gain.coerceAtMost(1f))).coerceAtLeast(0f)

    fun reset() {
        envelope = 0f
        gain = 1f
    }

    private fun coefficient(ms: Float): Float = exp(-1.0 / (0.001 * ms.coerceAtLeast(0.1f) * sampleRate)).toFloat()

    private fun softKneeGainDb(levelDb: Float, thresholdDb: Float, ratio: Float, kneeDb: Float): Float {
        val r = ratio.coerceAtLeast(1f)
        val slope = 1f / r - 1f
        val over = levelDb - thresholdDb
        val knee = kneeDb.coerceAtLeast(0.001f)
        return when {
            over <= -knee / 2f -> 0f
            over >= knee / 2f -> slope * over
            else -> {
                val x = over + knee / 2f
                slope * x * x / (2f * knee)
            }
        }
    }
}

internal class LinkwitzRileySplit {
    private val low1 = SmoothBiquad()
    private val low2 = SmoothBiquad()
    private val high1 = SmoothBiquad()
    private val high2 = SmoothBiquad()

    fun configure(sampleRate: Int, frequencyHz: Float) {
        val lp = BiquadDesigner.lowPass(sampleRate, frequencyHz)
        val hp = BiquadDesigner.highPass(sampleRate, frequencyHz)
        low1.setCoefficients(lp, 0)
        low2.setCoefficients(lp, 0)
        high1.setCoefficients(hp, 0)
        high2.setCoefficients(hp, 0)
    }

    fun split(input: Float): Pair<Float, Float> {
        val low = low2.process(low1.process(input))
        val high = high2.process(high1.process(input))
        return low to high
    }

    fun reset() {
        low1.reset(); low2.reset(); high1.reset(); high2.reset()
    }
}

internal class ThreeBandDynamics {
    private val splitLowL = LinkwitzRileySplit()
    private val splitLowR = LinkwitzRileySplit()
    private val splitHighL = LinkwitzRileySplit()
    private val splitHighR = LinkwitzRileySplit()
    private val lowComp = StereoLinkedCompressor()
    private val midComp = StereoLinkedCompressor()
    private val highComp = StereoLinkedCompressor()
    private var sampleRate = 48_000

    fun configure(sampleRate: Int) {
        this.sampleRate = sampleRate
        splitLowL.configure(sampleRate, 130f)
        splitLowR.configure(sampleRate, 130f)
        splitHighL.configure(sampleRate, 2_800f)
        splitHighR.configure(sampleRate, 2_800f)
        lowComp.configure(sampleRate)
        midComp.configure(sampleRate)
        highComp.configure(sampleRate)
    }

    fun process(left: Float, right: Float, amount: Float): Pair<Float, Float> {
        val a = amount.coerceIn(0f, 1f)
        if (a <= 0.001f) return left to right

        val (lowL, upperL) = splitLowL.split(left)
        val (lowR, upperR) = splitLowR.split(right)
        val (midL, highL) = splitHighL.split(upperL)
        val (midR, highR) = splitHighR.split(upperR)

        val ratio = 1f + 3.2f * a
        val knee = 4f + 4f * a
        val low = lowComp.process(lowL, lowR, -16f - 13f * a, ratio, 18f, 120f, knee, 0.4f * a)
        val mid = midComp.process(midL, midR, -19f - 12f * a, ratio, 8f, 90f, knee, 0.8f * a)
        val high = highComp.process(highL, highR, -17f - 10f * a, 1f + 2.6f * a, 4f, 70f, knee, 0.4f * a)
        return (low.first + mid.first + high.first) to (low.second + mid.second + high.second)
    }

    fun maxGainReductionDb(): Float = maxOf(lowComp.gainReductionDb(), midComp.gainReductionDb(), highComp.gainReductionDb())

    fun reset() {
        splitLowL.reset(); splitLowR.reset(); splitHighL.reset(); splitHighR.reset()
        lowComp.reset(); midComp.reset(); highComp.reset()
    }
}
