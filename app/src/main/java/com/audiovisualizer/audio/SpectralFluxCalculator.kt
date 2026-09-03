package com.audiovisualizer.audio

/**
 * Computes spectral flux - the total positive change in magnitude spectrum
 * between consecutive frames - which spikes sharply at percussive onsets.
 */
class SpectralFluxCalculator(private val binCount: Int) {

    private val previousMagnitude = FloatArray(binCount)

    /** Call once per frame, in time order, with that frame's magnitude spectrum. */
    fun nextFlux(magnitude: FloatArray): Float {
        var flux = 0f
        for (i in 0 until binCount) {
            val diff = magnitude[i] - previousMagnitude[i]
            if (diff > 0f) flux += diff
            previousMagnitude[i] = magnitude[i]
        }
        return flux
    }
}
