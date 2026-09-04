package com.audiovisualizer.render.gl

import com.audiovisualizer.audio.AudioFrame
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drives a fog layer's noise-coordinate offset so the pattern actually
 * travels across the picture, not just breathes in place. Direction and
 * speed both wander on their own long, irregular [AmbientCycle]s - exactly
 * like a layer's intensity - so the drift never depends on audio by
 * default.
 *
 * On top of that ambient base, the current bass/mid/treble levels (each
 * smoothed over several seconds, never read raw) nudge the drift
 * continuously: bass pulls it downward and speeds it up a little (an
 * "expanding, sinking" feel), treble adds a gentle curl to the heading, and
 * mid gives an overall speed lift. Because every band input is a multi-
 * second rolling average, none of this can ever snap on a single beat.
 */
class FogDriftAnimator(seed: Long) {

    class Result(val offsetX: Float, val offsetY: Float)

    private val directionCycle = AmbientCycle(seed, basePeriodSeconds = 40f)
    private val speedCycle = AmbientCycle(seed xor SPEED_SEED_MIX, basePeriodSeconds = 29f)

    private var smoothedBass = 0f
    private var smoothedMid = 0f
    private var smoothedTreble = 0f

    /** Accumulated treble-driven heading rotation, radians - persists across frames so it can spiral. */
    private var headingBias = 0f

    private var offsetX = 0f
    private var offsetY = 0f

    fun update(deltaSeconds: Float, elapsedSeconds: Float, frame: AudioFrame?): Result {
        if (deltaSeconds <= 0f) return Result(offsetX, offsetY)

        val bandAlpha = (deltaSeconds / BAND_SMOOTHING_SECONDS).coerceIn(0f, 1f)
        smoothedBass += ((frame?.bass ?: 0f) - smoothedBass) * bandAlpha
        smoothedMid += ((frame?.mid ?: 0f) - smoothedMid) * bandAlpha
        smoothedTreble += ((frame?.treble ?: 0f) - smoothedTreble) * bandAlpha

        // Own ambient motion first: a slowly wandering heading and speed,
        // never touching audio.
        val angle = directionCycle.value(elapsedSeconds) * TAU
        val speedMultiplier = 0.5f + speedCycle.value(elapsedSeconds) // 0.5..1.5
        var vx = cos(angle) * BASE_DRIFT_SPEED * speedMultiplier
        var vy = sin(angle) * BASE_DRIFT_SPEED * speedMultiplier

        // Mid: a gentle overall speed lift.
        val midBoost = 1f + smoothedMid * MID_SPEED_BOOST
        vx *= midBoost
        vy *= midBoost

        // Treble: a slow, *persistent* curl. headingBias keeps accumulating
        // while treble stays elevated, so the path actually spirals more
        // and more the longer treble is sustained - not just a one-frame
        // wobble that resets every frame. Still driven only by the
        // smoothed level, so a single beat barely moves it.
        headingBias += smoothedTreble * TREBLE_CURL_STRENGTH * deltaSeconds
        val cosC = cos(headingBias)
        val sinC = sin(headingBias)
        val curledVx = vx * cosC - vy * sinC
        val curledVy = vx * sinC + vy * cosC
        vx = curledVx
        vy = curledVy

        // Bass: pulls the drift downward and speeds it up - an
        // "expanding, sinking" feel, layered on top rather than replacing
        // the ambient heading.
        vy -= smoothedBass * BASS_PULL_STRENGTH

        offsetX += vx * deltaSeconds
        offsetY += vy * deltaSeconds

        return Result(offsetX, offsetY)
    }

    private companion object {
        const val TAU = (2.0 * Math.PI).toFloat()
        const val SPEED_SEED_MIX = 0x5DEECE66DL
        const val BASE_DRIFT_SPEED = 0.02f
        const val BAND_SMOOTHING_SECONDS = 3f
        const val MID_SPEED_BOOST = 0.5f
        const val TREBLE_CURL_STRENGTH = 0.25f
        const val BASS_PULL_STRENGTH = 0.03f
    }
}
