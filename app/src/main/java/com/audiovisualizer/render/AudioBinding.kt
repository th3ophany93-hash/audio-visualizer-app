package com.audiovisualizer.render

/** Which audio-analysis signal a binding reacts to. */
enum class AudioBand { BASS, MID, TREBLE, ONSET }

/** Which visual parameter an [AudioBinding] drives. */
enum class AudioTarget { SCALE, OPACITY, ROTATION, POSITION_Y, PARTICLE_SPAWN_RATE, EFFECT_INTENSITY }

/**
 * Ties a [Layer] (or one of its effects) to an [AudioBand] from
 * [com.audiovisualizer.audio.AudioAnalysisResult]. When absent, the layer
 * animates on its own (manual/no audio reactivity).
 */
data class AudioBinding(
    val band: AudioBand,
    val target: AudioTarget,
    val sensitivity: Float = 1f,
    val smoothing: Float = 0.2f,
    val minValue: Float = 0f,
    val maxValue: Float = 1f
)
