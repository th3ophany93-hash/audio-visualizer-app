package com.audiovisualizer.render.gl

import com.audiovisualizer.audio.AudioFrame
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.AudioBinding

/**
 * Resolves an [AudioBinding] against the current [AudioFrame] into the
 * scalar value a shader uniform or simulation parameter should use this
 * frame, exponentially smoothed frame-to-frame per layer so audio-reactive
 * motion doesn't jitter.
 */
class AudioBindingResolver {

    private val smoothedValues = HashMap<String, Float>()

    fun resolve(layerId: String, binding: AudioBinding?, frame: AudioFrame?): Float {
        if (binding == null) return 0f

        val bandValue = when (binding.band) {
            AudioBand.BASS -> frame?.bass ?: 0f
            AudioBand.MID -> frame?.mid ?: 0f
            AudioBand.TREBLE -> frame?.treble ?: 0f
            AudioBand.ONSET -> frame?.onsetStrength ?: 0f
        }
        val raw = (bandValue * binding.sensitivity).coerceIn(0f, 1f)

        val previous = smoothedValues[layerId] ?: raw
        val responsiveness = 1f - binding.smoothing.coerceIn(0f, 1f)
        val smoothed = previous + (raw - previous) * responsiveness
        smoothedValues[layerId] = smoothed

        return binding.minValue + smoothed * (binding.maxValue - binding.minValue)
    }
}
