package com.audiovisualizer.render.gl

import com.audiovisualizer.audio.AudioFrame
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.AudioBinding
import com.audiovisualizer.render.AudioTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerAnimatorTest {

    private val dt = 1f / 30f

    private fun frame(bass: Float) =
        AudioFrame(timeMs = 0, bass = bass, mid = 0f, treble = 0f, isOnset = false, onsetStrength = 0f)

    @Test
    fun `a layer without a binding animates from ambient alone, ignoring loud audio`() {
        val animator = LayerAnimator()
        val values = mutableListOf<Float>()
        var t = 0f
        while (t < 30f) {
            // Loud, constant bass the whole time - should have zero effect with no binding.
            values.add(animator.update("fog", null, frame(bass = 1f), dt).intensity)
            t += dt
        }
        assertTrue("expected the unbound layer to still vary over time", values.distinct().size > 5)
        assertTrue("expected values to stay in range", values.all { it in 0f..1f })
    }

    @Test
    fun `two layers with identical bindings and audio still animate independently`() {
        val animator = LayerAnimator()
        val binding = AudioBinding(band = AudioBand.BASS, target = AudioTarget.EFFECT_INTENSITY, smoothing = 0f)

        var diverged = false
        var t = 0f
        while (t < 30f) {
            val a = animator.update("layer-a", binding, frame(bass = 0.3f), dt).intensity
            val b = animator.update("layer-b", binding, frame(bass = 0.3f), dt).intensity
            if (kotlin.math.abs(a - b) > 0.05f) {
                diverged = true
                break
            }
            t += dt
        }
        assertTrue("expected two layers to diverge thanks to independent ambient phases", diverged)
    }

    @Test
    fun `a steady beat does not make a bound layer pulse`() {
        val animator = LayerAnimator()
        val binding = AudioBinding(band = AudioBand.BASS, target = AudioTarget.EFFECT_INTENSITY, smoothing = 0f)

        val values = mutableListOf<Float>()
        var t = 0f
        while (t < 20f) {
            val phase = t % 0.5f // 120 BPM
            val bass = if (phase < 0.1f) 1f else 0f
            values.add(animator.update("particles", binding, frame(bass), dt).intensity)
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
    fun `min and max value remap the combined ambient plus climax intensity`() {
        val animator = LayerAnimator()
        val binding = AudioBinding(
            band = AudioBand.BASS,
            target = AudioTarget.EFFECT_INTENSITY,
            smoothing = 0f,
            minValue = 2f,
            maxValue = 4f
        )
        val result = animator.update("layer", binding, frame(bass = 0f), dt)
        assertTrue("expected the remapped value to fall within [min, max]", result.intensity in 2f..4f)
    }

    @Test
    fun `elapsed seconds accumulate per layer across calls`() {
        val animator = LayerAnimator()
        val first = animator.update("layer", null, null, 1f)
        val second = animator.update("layer", null, null, 2f)
        assertEquals(1f, first.elapsedSeconds, 1e-4f)
        assertEquals(3f, second.elapsedSeconds, 1e-4f)
    }
}
