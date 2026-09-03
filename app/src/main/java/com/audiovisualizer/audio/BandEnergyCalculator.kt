package com.audiovisualizer.audio

data class BandEnergies(val bass: Float, val mid: Float, val treble: Float)

/** Averages FFT bin magnitudes falling into the bass/mid/treble frequency ranges. */
object BandEnergyCalculator {

    fun compute(
        magnitude: FloatArray,
        sampleRate: Int,
        fftSize: Int,
        bassRange: ClosedFloatingPointRange<Float>,
        midRange: ClosedFloatingPointRange<Float>,
        trebleRange: ClosedFloatingPointRange<Float>
    ): BandEnergies {
        var bassSum = 0f; var bassCount = 0
        var midSum = 0f; var midCount = 0
        var trebleSum = 0f; var trebleCount = 0

        val binCount = fftSize / 2
        val binHz = sampleRate.toFloat() / fftSize

        for (bin in 1 until binCount) { // skip DC bin
            val freq = bin * binHz
            val mag = magnitude[bin]
            when {
                freq in bassRange -> { bassSum += mag; bassCount++ }
                freq in midRange -> { midSum += mag; midCount++ }
                freq in trebleRange -> { trebleSum += mag; trebleCount++ }
            }
        }

        return BandEnergies(
            bass = if (bassCount > 0) bassSum / bassCount else 0f,
            mid = if (midCount > 0) midSum / midCount else 0f,
            treble = if (trebleCount > 0) trebleSum / trebleCount else 0f
        )
    }
}
