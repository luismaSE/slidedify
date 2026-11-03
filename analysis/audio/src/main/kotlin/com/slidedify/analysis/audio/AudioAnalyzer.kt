package com.slidedify.analysis.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** How a clip sounds, each value normalized to 0..1 except the tempo. */
data class AudioFeatures(
    /** 0 calm … 1 intense: loudness, how often new sounds hit, and brightness combined. */
    val energy: Float,
    /** 0 soft/dark … 1 bright/harsh (spectral centroid). */
    val brightness: Float,
    /** Beats per minute, or null when no steady beat was found. */
    val tempo: Float?,
    /** Raw measures, for tuning and tests. */
    val loudnessDb: Float,
    val onsetsPerSecond: Float,
)

/**
 * Small, dependency-free analysis of a mono clip (the 30 s preview): RMS loudness, spectral
 * centroid, onset rate from spectral flux, and tempo from the autocorrelation of the onset curve.
 * Heuristic on purpose: it only has to tell calm from intense songs, not transcribe them.
 */
object AudioAnalyzer {
    private const val TARGET_RATE = 11_025
    private const val FRAME = 1024
    private const val HOP = 256

    /** [samples] in -1..1. Returns null for clips shorter than 3 seconds. */
    fun analyze(samples: FloatArray, sampleRate: Int): AudioFeatures? {
        if (sampleRate <= 0 || samples.size < sampleRate * 3) return null
        val factor = max(1, sampleRate / TARGET_RATE)
        val signal = decimate(samples, factor)
        val rate = sampleRate.toFloat() / factor
        val frames = (signal.size - FRAME) / HOP
        if (frames < 16) return null

        val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).toFloat() }
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        val bins = FRAME / 2
        var previous = DoubleArray(bins)
        val rms = FloatArray(frames)
        val centroid = FloatArray(frames)
        val flux = FloatArray(frames)
        val spectrumTotal = FloatArray(frames)

        for (f in 0 until frames) {
            val start = f * HOP
            var sumSquares = 0.0
            for (i in 0 until FRAME) {
                val x = signal[start + i]
                sumSquares += x * x
                re[i] = (x * window[i]).toDouble()
                im[i] = 0.0
            }
            rms[f] = sqrt(sumSquares / FRAME).toFloat()
            fft(re, im)
            val magnitude = DoubleArray(bins) { k -> ln(1.0 + 100.0 * sqrt(re[k] * re[k] + im[k] * im[k])) }
            var weighted = 0.0
            var total = 0.0
            var rise = 0.0
            for (k in 1 until bins) {
                weighted += k * magnitude[k]
                total += magnitude[k]
                val d = magnitude[k] - previous[k]
                if (d > 0) rise += d
            }
            centroid[f] = if (total > 0) (weighted / total * rate / FRAME).toFloat() else 0f
            flux[f] = if (f == 0) 0f else rise.toFloat()
            spectrumTotal[f] = total.toFloat()
            previous = magnitude
        }

        val meanRms = rms.average().toFloat()
        val loudnessDb = if (meanRms > 1e-6f) (20 * log10(meanRms.toDouble())).toFloat() else -90f
        // Brightness from the louder frames only, so silences don't pull it down.
        val loudFrames = rms.indices.filter { rms[it] >= meanRms * 0.5f }
        val meanCentroid = loudFrames.map { centroid[it] }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
        val brightness = normalize(meanCentroid, 700f, 2600f)

        val frameRate = rate / HOP
        // A change smaller than a few percent of the spectrum is noise, not a new sound.
        val minFlux = 0.04f * spectrumTotal.sorted()[frames / 2]
        val onsets = countOnsets(flux, minFlux)
        val onsetsPerSecond = onsets / (frames / frameRate)
        val tempo = if (onsetsPerSecond >= 0.8f) estimateTempo(flux, frameRate) else null

        val energy = 0.45f * normalize(loudnessDb, -30f, -9f) +
            0.35f * normalize(onsetsPerSecond, 1f, 5.5f) +
            0.2f * brightness
        return AudioFeatures(energy.coerceIn(0f, 1f), brightness, tempo, loudnessDb, onsetsPerSecond)
    }

    private fun decimate(samples: FloatArray, factor: Int): FloatArray {
        if (factor == 1) return samples
        // Averaging is a crude low-pass, good enough for these measures.
        return FloatArray(samples.size / factor) { i ->
            var sum = 0f
            for (j in 0 until factor) sum += samples[i * factor + j]
            sum / factor
        }
    }

    /** Peaks of the flux curve that stand out of their neighbourhood. */
    private fun countOnsets(flux: FloatArray, minFlux: Float): Int {
        val half = 8
        var count = 0
        var lastPeak = -100
        for (i in 1 until flux.size - 1) {
            val from = max(0, i - half)
            val to = minOf(flux.size, i + half + 1)
            var sum = 0f
            for (j in from until to) sum += flux[j]
            val localMean = sum / (to - from)
            if (flux[i] > flux[i - 1] && flux[i] >= flux[i + 1] && flux[i] > localMean * 1.5f && flux[i] > minFlux && i - lastPeak >= 4) {
                count++
                lastPeak = i
            }
        }
        return count
    }

    /** Tempo from the autocorrelation of the onset curve, preferring values near 120 BPM. */
    private fun estimateTempo(flux: FloatArray, frameRate: Float): Float? {
        val mean = flux.average().toFloat()
        val envelope = FloatArray(flux.size) { max(0f, flux[it] - mean) }
        val minLag = (60f * frameRate / 200f).toInt().coerceAtLeast(1)
        val maxLag = (60f * frameRate / 60f).toInt().coerceAtMost(envelope.size / 2)
        if (maxLag <= minLag) return null

        var energy0 = 0.0
        for (x in envelope) energy0 += x * x
        if (energy0 <= 0) return null

        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            if (lag >= envelope.size) break
            var sum = 0.0
            for (i in 0 until envelope.size - lag) sum += envelope[i] * envelope[i + lag]
            scores[lag] = sum / energy0
        }
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * frameRate / lag
            // Log-gaussian preference around 120 BPM (octave errors are the usual failure).
            val prior = exp(-0.5 * (ln(bpm / 120.0) / 0.5).let { it * it })
            val score = scores[lag] * prior
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0 || scores[bestLag] < 0.1) return null
        // Parabolic interpolation for a finer lag.
        val l = scores.getOrElse(bestLag - 1) { scores[bestLag] }
        val c = scores[bestLag]
        val r = scores.getOrElse(bestLag + 1) { scores[bestLag] }
        val denominator = l - 2 * c + r
        val offset = if (denominator != 0.0) (0.5 * (l - r) / denominator).coerceIn(-0.5, 0.5) else 0.0
        return (60.0 * frameRate / (bestLag + offset)).toFloat()
    }

    private fun normalize(value: Float, low: Float, high: Float) = ((value - low) / (high - low)).coerceIn(0f, 1f)

    /** In-place iterative radix-2 FFT; size must be a power of two. */
    internal fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
