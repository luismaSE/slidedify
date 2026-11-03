package com.slidedify.analysis.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioAnalyzerTest {
    private val rate = 44_100

    /** Short noise bursts on every beat over a steady bed, like a dense mix with drums. */
    private fun beat(bpm: Double, seconds: Int = 20, amplitude: Float = 0.8f): FloatArray {
        val random = Random(1)
        val out = FloatArray(rate * seconds) { (random.nextFloat() * 2 - 1) * amplitude * 0.25f }
        val period = (rate * 60.0 / bpm).toInt()
        val burst = rate / 25
        var start = 0
        while (start < out.size) {
            for (i in 0 until burst) {
                if (start + i >= out.size) break
                val decay = 1f - i.toFloat() / burst
                out[start + i] += (random.nextFloat() * 2 - 1) * amplitude * decay
            }
            start += period
        }
        return out
    }

    /** A quiet, steady low tone: as calm as it gets. */
    private fun softTone(seconds: Int = 20) = FloatArray(rate * seconds) { (0.05 * sin(2 * PI * 220 * it / rate)).toFloat() }

    @Test
    fun `finds the tempo of a steady beat`() {
        for (bpm in listOf(90.0, 120.0, 140.0)) {
            val tempo = assertNotNull(AudioAnalyzer.analyze(beat(bpm), rate)?.tempo, "tempo for $bpm")
            // Half or double tempo would also be acceptable for suggestions, but here it should be exact.
            assertTrue(abs(tempo - bpm) < 4, "expected ~$bpm, got $tempo")
        }
    }

    @Test
    fun `a loud busy beat is more energetic than a soft tone`() {
        val busy = AudioAnalyzer.analyze(beat(150.0, amplitude = 0.9f), rate)!!
        val calm = AudioAnalyzer.analyze(softTone(), rate)!!
        assertTrue(busy.energy > calm.energy + 0.3f, "busy=${busy.energy} calm=${calm.energy}")
        assertTrue(calm.energy < 0.3f)
        assertTrue(busy.brightness > calm.brightness)
    }

    @Test
    fun `a steady tone has no beat`() {
        assertNull(AudioAnalyzer.analyze(softTone(), rate)!!.tempo)
    }

    @Test
    fun `clips that are too short are ignored`() {
        assertNull(AudioAnalyzer.analyze(FloatArray(rate), rate))
    }

    @Test
    fun `fft finds a pure tone`() {
        val n = 64
        val re = DoubleArray(n) { sin(2 * PI * 5 * it / n) }
        val im = DoubleArray(n)
        AudioAnalyzer.fft(re, im)
        val peak = (0 until n / 2).maxBy { re[it] * re[it] + im[it] * im[it] }
        assertEquals(5, peak)
    }
}
