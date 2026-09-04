package com.audiovisualizer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformExtractorTest {

    @Test
    fun `bucket count is honored exactly`() {
        val samples = FloatArray(1000) { 0.5f }
        val peaks = WaveformExtractor.peaks(samples, 100)
        assertEquals(100, peaks.size)
    }

    @Test
    fun `each bucket reports the peak absolute amplitude within its span`() {
        // Bucket 0 covers samples 0..9 (10 samples per bucket for 100 samples / 10 buckets):
        // a single loud negative spike should still register as a high peak (absolute value).
        val samples = FloatArray(100) { 0.1f }
        samples[5] = -0.9f
        val peaks = WaveformExtractor.peaks(samples, 10)
        assertEquals(0.9f, peaks[0], 1e-4f)
        assertEquals(0.1f, peaks[1], 1e-4f) // untouched bucket stays at the baseline amplitude
    }

    @Test
    fun `silence produces all-zero peaks`() {
        val peaks = WaveformExtractor.peaks(FloatArray(500), 50)
        assertTrue(peaks.all { it == 0f })
    }

    @Test
    fun `empty input or non-positive bucket count returns an empty array instead of crashing`() {
        assertEquals(0, WaveformExtractor.peaks(FloatArray(0), 50).size)
        assertEquals(0, WaveformExtractor.peaks(FloatArray(100), 0).size)
        assertEquals(0, WaveformExtractor.peaks(FloatArray(100), -5).size)
    }

    @Test
    fun `bucket count larger than sample count still returns exactly that many buckets`() {
        val peaks = WaveformExtractor.peaks(FloatArray(5) { 0.3f }, 20)
        assertEquals(20, peaks.size)
    }
}
