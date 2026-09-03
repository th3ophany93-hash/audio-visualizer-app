package com.audiovisualizer.render.gl

import kotlin.math.sin
import kotlin.random.Random

/**
 * A layer's "always on" base motion: a smooth 0..1 value that drifts on
 * long, irregular cycles of its own, completely independent of the music.
 * This is what keeps an ambient layer (fog, glow, particles) alive between
 * musical climaxes instead of sitting frozen, and - just as importantly -
 * what keeps it from ever looking like it's pulsing to the beat, since it
 * never reads audio at all.
 *
 * Built from a handful of sine waves with different, non-integer-related
 * periods and a random phase per component (seeded per layer, so no two
 * layers drift in lockstep), summed and normalized. The combined signal
 * wanders for a long time before its pattern becomes obvious.
 */
class AmbientCycle(seed: Long, basePeriodSeconds: Float = 25f) {

    private class Component(val periodSeconds: Float, val phase: Float, val weight: Float)

    private val components: List<Component>
    private val totalWeight: Float

    init {
        val random = Random(seed)
        components = List(COMPONENT_COUNT) { index ->
            val periodSeconds = basePeriodSeconds * (0.6f + random.nextFloat() * 1.8f) * (index + 1) / 2f
            val phase = random.nextFloat() * TAU
            val weight = 1f / (index + 1) // later components contribute less, like a simple 1/f blend
            Component(periodSeconds, phase, weight)
        }
        totalWeight = components.sumOf { it.weight.toDouble() }.toFloat()
    }

    /** 0..1, smoothly wandering as a pure function of [elapsedSeconds] since this cycle started. */
    fun value(elapsedSeconds: Float): Float {
        var sum = 0f
        for (component in components) {
            val angle = (elapsedSeconds / component.periodSeconds) * TAU + component.phase
            sum += (sin(angle) * 0.5f + 0.5f) * component.weight
        }
        return (sum / totalWeight).coerceIn(0f, 1f)
    }

    private companion object {
        const val COMPONENT_COUNT = 4
        const val TAU = (2.0 * Math.PI).toFloat()
    }
}
