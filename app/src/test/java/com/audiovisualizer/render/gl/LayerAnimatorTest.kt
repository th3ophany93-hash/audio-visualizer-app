package com.audiovisualizer.render.gl

import com.audiovisualizer.audio.AudioFrame
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.effects.EffectParams
import com.audiovisualizer.render.effects.ReactionMode
import com.audiovisualizer.render.effects.ReactionTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerAnimatorTest {

    private val dt = 1f / 30f

    private fun frame(bass: Float, isOnset: Boolean = false) =
        AudioFrame(timeMs = 0, bass = bass, mid = 0f, treble = 0f, isOnset = isOnset, onsetStrength = if (isOnset) 1f else 0f)

    @Test
    fun `AMBIENT_ONLY (the default) animates from ambient alone, ignoring loud audio`() {
        val animator = LayerAnimator()
        val params = EffectParams() // default reactionMode = AMBIENT_ONLY
        val values = mutableListOf<Float>()
        var t = 0f
        while (t < 30f) {
            // Loud, constant bass the whole time - should have zero effect in AMBIENT_ONLY.
            values.add(animator.update("fog", params, frame(bass = 1f), dt).intensity)
            t += dt
        }
        assertTrue("expected the layer to still vary over time", values.distinct().size > 5)
        assertTrue("expected values to stay in range", values.all { it in 0f..1f })
    }

    @Test
    fun `NONE reaction mode holds a constant full intensity regardless of audio`() {
        val animator = LayerAnimator()
        val params = EffectParams(reactionMode = ReactionMode.NONE)
        var t = 0f
        while (t < 10f) {
            val intensity = animator.update("layer", params, frame(bass = if (t < 5f) 1f else 0f), dt).intensity
            assertTrue("expected NONE to stay pinned near 1.0, got $intensity at t=$t", intensity > 0.99f)
            t += dt
        }
    }

    @Test
    fun `two SMOOTH_CLIMAX layers with identical tuning and audio still animate independently`() {
        val animator = LayerAnimator()
        val params = EffectParams(reactionMode = ReactionMode.SMOOTH_CLIMAX, reactionTuning = ReactionTuning(band = AudioBand.BASS))

        var diverged = false
        var t = 0f
        while (t < 30f) {
            val a = animator.update("layer-a", params, frame(bass = 0.3f), dt).intensity
            val b = animator.update("layer-b", params, frame(bass = 0.3f), dt).intensity
            if (kotlin.math.abs(a - b) > 0.05f) {
                diverged = true
                break
            }
            t += dt
        }
        assertTrue("expected two layers to diverge thanks to independent ambient phases", diverged)
    }

    @Test
    fun `SMOOTH_CLIMAX does not make a bound layer pulse on a steady beat`() {
        val animator = LayerAnimator()
        val params = EffectParams(reactionMode = ReactionMode.SMOOTH_CLIMAX, reactionTuning = ReactionTuning(band = AudioBand.BASS))

        val values = mutableListOf<Float>()
        var t = 0f
        while (t < 20f) {
            val phase = t % 0.5f // 120 BPM
            val bass = if (phase < 0.1f) 1f else 0f
            values.add(animator.update("particles", params, frame(bass), dt).intensity)
            t += dt
        }

        // Look only at the steady-state tail (past the first few seconds,
        // once the ambient wander's own slow motion is the dominant thing
        // changing) and check consecutive-frame jumps stay small - no
        // sawtooth "beat -> spike -> beat -> spike" pattern.
        val tail = values.drop(values.size / 2)
        val maxFrameToFrameJump = tail.zipWithNext { a, b -> kotlin.math.abs(b - a) }.max()
        assertTrue(
            "expected smooth frame-to-frame motion even with a steady beat, max jump was $maxFrameToFrameJump",
            maxFrameToFrameJump < 0.05f
        )
    }

    @Test
    fun `minIntensity and maxIntensity remap the combined reaction value`() {
        val animator = LayerAnimator()
        val params = EffectParams(
            reactionMode = ReactionMode.SMOOTH_CLIMAX,
            reactionTuning = ReactionTuning(band = AudioBand.BASS, minIntensity = 2f, maxIntensity = 4f)
        )
        val result = animator.update("layer", params, frame(bass = 0f), dt)
        assertTrue("expected the remapped value to fall within [min, max]", result.intensity in 2f..4f)
    }

    @Test
    fun `BEAT_PULSE reacts to a single hit much faster than SMOOTH_CLIMAX`() {
        val animator = LayerAnimator()
        val pulseParams = EffectParams(
            reactionMode = ReactionMode.BEAT_PULSE,
            reactionTuning = ReactionTuning(band = AudioBand.BASS, attackSeconds = 0.03f, releaseSeconds = 0.2f)
        )
        val climaxParams = EffectParams(
            reactionMode = ReactionMode.SMOOTH_CLIMAX,
            reactionTuning = ReactionTuning(band = AudioBand.BASS)
        )

        // Warm both up on silence first so we're comparing their response to
        // the same single hit, not their differing startup transients.
        var t = 0f
        while (t < 2f) {
            animator.update("pulse", pulseParams, frame(bass = 0f), dt)
            animator.update("climax", climaxParams, frame(bass = 0f), dt)
            t += dt
        }

        val pulseBefore = animator.update("pulse", pulseParams, frame(bass = 0f), dt).intensity
        val climaxBefore = animator.update("climax", climaxParams, frame(bass = 0f), dt).intensity

        // One frame of a hard hit.
        val pulseAfterHit = animator.update("pulse", pulseParams, frame(bass = 1f), dt).intensity
        val climaxAfterHit = animator.update("climax", climaxParams, frame(bass = 1f), dt).intensity

        assertTrue(
            "expected BEAT_PULSE to jump noticeably within one frame of a hit (before=$pulseBefore, after=$pulseAfterHit)",
            pulseAfterHit - pulseBefore > 0.2f
        )
        assertTrue(
            "expected SMOOTH_CLIMAX to barely move on a single frame (before=$climaxBefore, after=$climaxAfterHit)",
            climaxAfterHit - climaxBefore < 0.05f
        )
    }

    @Test
    fun `beatFlicker is off by default and does nothing without an onset`() {
        val animator = LayerAnimator()
        val params = EffectParams(reactionMode = ReactionMode.AMBIENT_ONLY)
        assertTrue("beatFlicker should default to false", !params.beatFlicker)

        var t = 0f
        var lastIntensity = 0f
        while (t < 5f) {
            lastIntensity = animator.update("layer", params, frame(bass = 0f, isOnset = false), dt).intensity
            t += dt
        }
        assertTrue("intensity should stay in range with flicker off", lastIntensity in 0f..1f)
    }

    @Test
    fun `beatFlicker spikes on an onset and decays back to baseline`() {
        val animator = LayerAnimator()
        val params = EffectParams(reactionMode = ReactionMode.AMBIENT_ONLY, beatFlicker = true)

        // AMBIENT_ONLY's own wander is slow (tens-of-seconds periods), so
        // over the ~1s this test runs it's effectively a fixed baseline -
        // any big jump-then-return is the flicker, not the ambient cycle.
        val baseline = animator.update("layer", params, frame(bass = 0f, isOnset = false), dt).intensity
        val onFlash = animator.update("layer", params, frame(bass = 0f, isOnset = true), dt).intensity
        assertTrue(
            "expected an onset to spike intensity well above baseline (baseline=$baseline, onFlash=$onFlash)",
            onFlash - baseline > 0.3f
        )

        var afterDecay = onFlash
        var t = 0f
        while (t < 1f) {
            afterDecay = animator.update("layer", params, frame(bass = 0f, isOnset = false), dt).intensity
            t += dt
        }
        assertTrue(
            "expected the flicker's contribution to have decayed back near baseline within 1s " +
                "(baseline=$baseline, onFlash=$onFlash, afterDecay=$afterDecay)",
            afterDecay - baseline < 0.05f
        )
    }

    @Test
    fun `elapsed seconds accumulate per layer across calls`() {
        val animator = LayerAnimator()
        val params = EffectParams()
        val first = animator.update("layer", params, null, 1f)
        val second = animator.update("layer", params, null, 2f)
        assertEquals(1f, first.elapsedSeconds, 1e-4f)
        assertEquals(3f, second.elapsedSeconds, 1e-4f)
    }
}
