package com.audiovisualizer.audio

/** Per-frame analysis: normalized band energies (0..1) and onset detection at [timeMs]. */
data class AudioFrame(
    val timeMs: Long,
    val bass: Float,
    val mid: Float,
    val treble: Float,
    val isOnset: Boolean,
    val onsetStrength: Float
)

/** The full time-indexed result of [AudioAnalyzer.analyze], ready to drive rendering. */
class AudioAnalysisResult(
    val frames: List<AudioFrame>,
    val durationMs: Long,
    val sampleRate: Int,
    val frameStepMs: Float
) {
    /** The analysis frame covering [timeMs], or null if there are no frames. */
    fun frameAt(timeMs: Long): AudioFrame? {
        if (frames.isEmpty()) return null
        if (frameStepMs <= 0f) return frames.first()
        val index = (timeMs / frameStepMs).toInt().coerceIn(0, frames.size - 1)
        return frames[index]
    }
}
