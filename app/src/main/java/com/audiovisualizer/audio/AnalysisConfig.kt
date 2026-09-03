package com.audiovisualizer.audio

/** Tuning parameters for [AudioAnalyzer]. */
data class AnalysisConfig(
    val fftSize: Int = 2048,
    val hopSize: Int = 1024,
    val bassRange: ClosedFloatingPointRange<Float> = 20f..250f,
    val midRange: ClosedFloatingPointRange<Float> = 250f..4000f,
    val trebleRange: ClosedFloatingPointRange<Float> = 4000f..16000f,
    val onsetSensitivity: Float = 1.5f,
    val onsetWindowRadius: Int = 5
) {
    init {
        require(fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two, got $fftSize" }
        require(hopSize in 1..fftSize) { "hopSize must be in 1..fftSize" }
    }
}
