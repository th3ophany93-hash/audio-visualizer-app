package com.audiovisualizer.render.gl

import com.audiovisualizer.audio.AudioFrame
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.effects.EffectParams
import com.audiovisualizer.render.effects.ReactionMode

/**
 * Drives an effect layer's animated 0..1 intensity from its [EffectParams].
 * Every layer always has its own [AmbientCycle] wander running (seeded from
 * its id, so no two layers ever move in lockstep); [EffectParams.reactionMode]
 * decides whether - and how - audio gets layered on top of that, and
 * [EffectParams.beatFlicker] is an independent hard flicker on top of
 * whichever mode is chosen.
 */
class LayerAnimator {
    class Result(val intensity: Float, val elapsedSeconds: Float)

    private class LayerState(seed: Long) {
        val ambient = AmbientCycle(seed)
        var climax: ClimaxDetector? = null
        var pulseEnvelope = 0f
        var flickerLevel = 0f
        var elapsedSeconds = 0f
    }

    private val states = HashMap<String, LayerState>()

    fun update(layerId: String, params: EffectParams, frame: AudioFrame?, deltaSeconds: Float): Result {
        val state = states.getOrPut(layerId) { LayerState(layerId.hashCode().toLong()) }
        state.elapsedSeconds += deltaSeconds

        val ambientValue = state.ambient.value(state.elapsedSeconds)
        val tuning = params.reactionTuning

        val reactive = when (params.reactionMode) {
            ReactionMode.NONE -> 1f
            ReactionMode.AMBIENT_ONLY -> ambientValue
            ReactionMode.SMOOTH_CLIMAX -> {
                val climax = state.climax ?: ClimaxDetector(
                    riseSeconds = tuning.attackSeconds,
                    fallSeconds = tuning.releaseSeconds,
                    threshold = tuning.threshold
                ).also { state.climax = it }
                val bandValue = (bandValue(tuning.band, frame) * tuning.sensitivity).coerceIn(0f, 1f)
                val boost = climax.update(deltaSeconds, bandValue)
                (ambientValue + boost * (1f - ambientValue)).coerceIn(0f, 1f)
            }
            ReactionMode.BEAT_PULSE -> {
                // Unlike SMOOTH_CLIMAX (deliberately "ambient wander plus a
                // rare boost", so it still feels alive with no audio at
                // all), BEAT_PULSE is meant to be a *direct* read of the
                // beat: the envelope alone, not blended with the
                // independent ambient cycle. Blending it in here would mean
                // a layer could read as "reacting" purely because its
                // ambient phase happened to be high at that instant, with
                // zero correlation to the music - precisely indistinguishable
                // from AMBIENT_ONLY to a listener.
                val target = (bandValue(tuning.band, frame) * tuning.sensitivity - tuning.threshold).coerceIn(0f, 1f)
                val envelopeSeconds = (if (target > state.pulseEnvelope) tuning.attackSeconds else tuning.releaseSeconds)
                    .coerceAtLeast(0.001f)
                val alpha = (deltaSeconds / envelopeSeconds).coerceIn(0f, 1f)
                state.pulseEnvelope += (target - state.pulseEnvelope) * alpha
                state.pulseEnvelope
            }
        }

        var combined = reactive
        if (params.beatFlicker) {
            if (frame?.isOnset == true) state.flickerLevel = 1f
            val decayAlpha = (deltaSeconds / FLICKER_DECAY_SECONDS).coerceIn(0f, 1f)
            state.flickerLevel -= state.flickerLevel * decayAlpha
            combined = (combined + state.flickerLevel).coerceIn(0f, 1f)
        }

        val intensity = tuning.minIntensity + combined * (tuning.maxIntensity - tuning.minIntensity)
        return Result(intensity, state.elapsedSeconds)
    }

    private fun bandValue(band: AudioBand, frame: AudioFrame?): Float = when (band) {
        AudioBand.BASS -> frame?.bass ?: 0f
        AudioBand.MID -> frame?.mid ?: 0f
        AudioBand.TREBLE -> frame?.treble ?: 0f
        AudioBand.ONSET -> frame?.onsetStrength ?: 0f
    }

    private companion object {
        const val FLICKER_DECAY_SECONDS = 0.12f
    }
}
