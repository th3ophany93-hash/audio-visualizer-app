package com.audiovisualizer.render.gl

import com.audiovisualizer.audio.AudioFrame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class FogDriftAnimatorTest {

    private val dt = 1f / 30f

    private fun frame(bass: Float = 0f, mid: Float = 0f, treble: Float = 0f) =
        AudioFrame(timeMs = 0, bass = bass, mid = mid, treble = treble, isOnset = false, onsetStrength = 0f)

    @Test
    fun `drift accumulates smoothly without any audio`() {
        val animator = FogDriftAnimator(seed = 1L)
        var elapsed = 0f
        var previous = FogDriftAnimator.Result(0f, 0f)
        var maxStep = 0f
        var t = 0f
        while (t < 60f) {
            elapsed += dt
            val result = animator.update(dt, elapsed, null)
            val step = hypot((result.offsetX - previous.offsetX).toDouble(), (result.offsetY - previous.offsetY).toDouble()).toFloat()
            maxStep = maxOf(maxStep, step)
            previous = result
            t += dt
        }
        assertTrue("expected the offset to actually move over a minute", hypot(previous.offsetX.toDouble(), previous.offsetY.toDouble()) > 0.05)
        assertTrue("expected smooth per-frame motion, max single-frame step was $maxStep", maxStep < 0.01f)
    }

    @Test
    fun `two differently-seeded fog layers drift differently`() {
        val a = FogDriftAnimator(seed = 10L)
        val b = FogDriftAnimator(seed = 20L)

        var elapsed = 0f
        var diverged = false
        var t = 0f
        while (t < 40f) {
            elapsed += dt
            val ra = a.update(dt, elapsed, null)
            val rb = b.update(dt, elapsed, null)
            val distance = hypot((ra.offsetX - rb.offsetX).toDouble(), (ra.offsetY - rb.offsetY).toDouble())
            if (distance > 0.02) {
                diverged = true
                break
            }
            t += dt
        }
        assertTrue("expected two independently-seeded fog drifts to diverge", diverged)
    }

    @Test
    fun `sustained bass pulls the drift downward relative to silence`() {
        // Same seed on both, so the only difference is the audio input -
        // isolates bass's contribution from the shared ambient component.
        val quiet = FogDriftAnimator(seed = 42L)
        val loud = FogDriftAnimator(seed = 42L)

        var elapsed = 0f
        var quietResult = FogDriftAnimator.Result(0f, 0f)
        var loudResult = FogDriftAnimator.Result(0f, 0f)
        var t = 0f
        while (t < 20f) {
            elapsed += dt
            quietResult = quiet.update(dt, elapsed, null)
            loudResult = loud.update(dt, elapsed, frame(bass = 1f))
            t += dt
        }

        assertTrue(
            "expected sustained bass to pull offsetY more negative than silence " +
                "(quiet=${quietResult.offsetY}, loud=${loudResult.offsetY})",
            loudResult.offsetY < quietResult.offsetY - 0.05f
        )
    }

    @Test
    fun `a single short spike does not cause a large directional jump`() {
        val animator = FogDriftAnimator(seed = 7L)
        var elapsed = 0f
        var previous = FogDriftAnimator.Result(0f, 0f)
        var maxStep = 0f
        var t = 0f
        while (t < 15f) {
            elapsed += dt
            val bandValue = if (t in 5f..5.1f) 1f else 0f
            val result = animator.update(dt, elapsed, frame(bass = bandValue, mid = bandValue, treble = bandValue))
            val step = hypot((result.offsetX - previous.offsetX).toDouble(), (result.offsetY - previous.offsetY).toDouble()).toFloat()
            maxStep = maxOf(maxStep, step)
            previous = result
            t += dt
        }
        assertTrue("a single beat should never cause a large single-frame jump, got $maxStep", maxStep < 0.01f)
    }

    @Test
    fun `treble curl changes the heading over time compared to silence`() {
        val straight = FogDriftAnimator(seed = 99L)
        val curling = FogDriftAnimator(seed = 99L)

        // Track the largest divergence seen at any point, not just the
        // final one: a continuously curling path can spiral back close to
        // the straight one by coincidence at any single sampled instant,
        // even though the heading is clearly different throughout.
        var elapsed = 0f
        var maxDistance = 0.0
        var t = 0f
        while (t < 20f) {
            elapsed += dt
            val straightResult = straight.update(dt, elapsed, null)
            val curlingResult = curling.update(dt, elapsed, frame(treble = 1f))
            val distance = hypot(
                (straightResult.offsetX - curlingResult.offsetX).toDouble(),
                (straightResult.offsetY - curlingResult.offsetY).toDouble()
            )
            maxDistance = maxOf(maxDistance, distance)
            t += dt
        }
        assertTrue("expected sustained treble to visibly bend the path away from the silent one, max distance was $maxDistance", maxDistance > 0.05)
    }
}
