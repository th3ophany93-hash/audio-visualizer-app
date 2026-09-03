package com.audiovisualizer.audio

import kotlin.math.PI
import kotlin.math.cos

/** Analysis windows applied to a frame before FFT to reduce spectral leakage. */
object WindowFunction {

    fun hann(size: Int): FloatArray = FloatArray(size) { i ->
        if (size == 1) 1f else (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
    }
}
