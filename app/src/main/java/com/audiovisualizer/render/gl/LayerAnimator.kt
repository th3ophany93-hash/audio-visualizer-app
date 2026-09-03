package com.audiovisualizer.render.gl

import com.audiovisualizer.audio.AudioFrame
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.AudioBinding

/**
 * Computes each ambient layer's (particles, fog, glow) animation intensity
 * as an always-on [AmbientCycle] wander plus, only when the layer has an
 * [AudioBinding], a rare [ClimaxDetector] boost on top. This is the whole
 * point: a layer's base motion never depends on audio, and the audio it
 * does react to is filtered down to sustained, unusual peaks - so nothing
 * ever pulses in lockstep with individual beats.
 *
 * Each layer keeps its own [AmbientCycle] and [ClimaxDetector], keyed by
 * [Layer.id][com.audiovisualizer.render.Layer], so layers never share
 * state or drift in sync with each other.
 */
class LayerAnimator {

    class Result(val intensity: Float, val elapsedSeconds: Float)

    private class LayerState(seed: Long) {
        val ambient = AmbientCycle(seed)
        var climax: ClimaxDetector? = null
        var elapsedSeconds = 0f
    }

    private val states = HashMap<String, LayerState>()

    fun update(layerId: String, binding: AudioBinding?, frame: AudioFrame?, deltaSeconds: Float): Result {
        val state = states.getOrPut(layerId) { LayerState(layerId.hashCode().toLong()) }
        state.elapsedSeconds += deltaSeconds

        val ambientValue = state.ambient.value(state.elapsedSeconds)
        if (binding == null) {
            return Result(ambientValue, state.elapsedSeconds)
        }

        val climax = state.climax ?: ClimaxDetector().also { state.climax = it }
        val bandValue = when (binding.band) {
            AudioBand.BASS -> frame?.bass ?: 0f
            AudioBand.MID -> frame?.mid ?: 0f
            AudioBand.TREBLE -> frame?.treble ?: 0f
            AudioBand.ONSET -> frame?.onsetStrength ?: 0f
        } * binding.sensitivity
        val climaxBoost = climax.update(deltaSeconds, bandValue.coerceIn(0f, 1f))

        // The climax can only ever lift the ambient wander toward the top
        // of the range - it's an occasional boost, not a replacement for
        // the base motion, and it fades back into that base motion (rather
        // than snapping to zero) once the climax passes.
        val combined = (ambientValue + climaxBoost * (1f - ambientValue)).coerceIn(0f, 1f)
        val intensity = binding.minValue + combined * (binding.maxValue - binding.minValue)
        return Result(intensity, state.elapsedSeconds)
    }
}
