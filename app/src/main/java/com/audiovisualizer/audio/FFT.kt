package com.audiovisualizer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place iterative radix-2 Cooley-Tukey FFT.
 * Both [real] and [imag] must have the same power-of-two length.
 */
object FFT {

    fun transform(real: FloatArray, imag: FloatArray) {
        val n = real.size
        require(imag.size == n) { "real and imag arrays must have the same size" }
        require(n and (n - 1) == 0 && n > 0) { "FFT size must be a power of two, got $n" }
        if (n == 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        // Iterative butterfly passes.
        var len = 2
        while (len <= n) {
            val half = len / 2
            val angle = -2.0 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curWr = 1f
                var curWi = 0f
                for (k in 0 until half) {
                    val evenIndex = i + k
                    val oddIndex = evenIndex + half
                    val oddReal = real[oddIndex] * curWr - imag[oddIndex] * curWi
                    val oddImag = real[oddIndex] * curWi + imag[oddIndex] * curWr
                    val evenReal = real[evenIndex]
                    val evenImag = imag[evenIndex]
                    real[evenIndex] = evenReal + oddReal
                    imag[evenIndex] = evenImag + oddImag
                    real[oddIndex] = evenReal - oddReal
                    imag[oddIndex] = evenImag - oddImag
                    val nextWr = curWr * wr - curWi * wi
                    val nextWi = curWr * wi + curWi * wr
                    curWr = nextWr
                    curWi = nextWi
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Magnitude spectrum |X[k]| = sqrt(re^2 + im^2), written into [out] (or a new array). */
    fun magnitude(real: FloatArray, imag: FloatArray, out: FloatArray = FloatArray(real.size)): FloatArray {
        for (i in real.indices) {
            out[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }
        return out
    }

    /** Smallest power of two >= [value]. */
    fun nextPowerOfTwo(value: Int): Int {
        var p = 1
        while (p < value) p = p shl 1
        return p
    }
}
