package com.audiovisualizer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FFTTest {

    @Test
    fun `DC signal produces energy only in bin 0`() {
        val n = 64
        val real = FloatArray(n) { 1f }
        val imag = FloatArray(n)

        FFT.transform(real, imag)
        val magnitude = FFT.magnitude(real, imag)

        assertEquals(n.toFloat(), magnitude[0], 1e-3f)
        for (i in 1 until n) {
            assertTrue("bin $i should be ~0 but was ${magnitude[i]}", magnitude[i] < 1e-3f)
        }
    }

    @Test
    fun `pure sine wave peaks at its own bin`() {
        val n = 256
        val targetBin = 10
        val real = FloatArray(n) { i -> sin(2.0 * PI * targetBin * i / n).toFloat() }
        val imag = FloatArray(n)

        FFT.transform(real, imag)
        val magnitude = FFT.magnitude(real, imag)

        var peakBin = 0
        for (i in 1 until n / 2) {
            if (magnitude[i] > magnitude[peakBin]) peakBin = i
        }
        assertEquals(targetBin, peakBin)
    }

    @Test
    fun `nextPowerOfTwo rounds up`() {
        assertEquals(1, FFT.nextPowerOfTwo(1))
        assertEquals(1024, FFT.nextPowerOfTwo(1000))
        assertEquals(1024, FFT.nextPowerOfTwo(1024))
    }
}
