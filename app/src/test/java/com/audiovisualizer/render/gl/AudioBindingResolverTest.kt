package com.audiovisualizer.render.gl

import com.audiovisualizer.audio.AudioFrame
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.AudioBinding
import com.audiovisualizer.render.AudioTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBindingResolverTest {

    private fun frame(bass: Float = 0f, mid: Float = 0f, treble: Float = 0f, onset: Boolean = false, onsetStrength: Float = 0f) =
        AudioFrame(timeMs = 0, bass = bass, mid = mid, treble = treble, isOnset = onset, onsetStrength = onsetStrength)

    @Test
    fun `no binding resolves to zero`() {
        val resolver = AudioBindingResolver()
        assertEquals(0f, resolver.resolve("layer", null, frame(bass = 1f)), 1e-6f)
    }

    @Test
    fun `picks the bound band`() {
        val resolver = AudioBindingResolver()
        val binding = AudioBinding(band = AudioBand.BASS, target = AudioTarget.PARTICLE_SPAWN_RATE, smoothing = 0f)
        val value = resolver.resolve("layer", binding, frame(bass = 0.8f, mid = 0.1f, treble = 0.1f))
        assertEquals(0.8f, value, 1e-4f)
    }

    @Test
    fun `no smoothing tracks the raw value immediately`() {
        val resolver = AudioBindingResolver()
        val binding = AudioBinding(band = AudioBand.BASS, target = AudioTarget.SCALE, smoothing = 0f)

        val first = resolver.resolve("layer", binding, frame(bass = 1f))
        assertEquals(1f, first, 1e-4f)

        val second = resolver.resolve("layer", binding, frame(bass = 0f))
        assertEquals(0f, second, 1e-4f)
    }

    @Test
    fun `heavy smoothing moves slowly toward the raw value`() {
        val resolver = AudioBindingResolver()
        val binding = AudioBinding(band = AudioBand.BASS, target = AudioTarget.SCALE, smoothing = 0.9f)

        resolver.resolve("layer", binding, frame(bass = 0f))
        val afterOneHit = resolver.resolve("layer", binding, frame(bass = 1f))

        assertTrue("expected a small step toward 1.0, got $afterOneHit", afterOneHit in 0.0f..0.2f)
    }

    @Test
    fun `min and max value remap the resolved intensity`() {
        val resolver = AudioBindingResolver()
        val binding = AudioBinding(
            band = AudioBand.BASS,
            target = AudioTarget.SCALE,
            smoothing = 0f,
            minValue = 1f,
            maxValue = 2f
        )
        val value = resolver.resolve("layer", binding, frame(bass = 1f))
        assertEquals(2f, value, 1e-4f)
    }

    // --- Layer independence: an image layer bound to one band and a
    // particles layer bound to another must never leak into each other,
    // even when resolved against the same frame by the same resolver. ---

    @Test
    fun `two layers bound to different bands react only to their own band`() {
        val resolver = AudioBindingResolver()
        val imageBinding = AudioBinding(band = AudioBand.MID, target = AudioTarget.OPACITY, smoothing = 0f)
        val particlesBinding = AudioBinding(band = AudioBand.BASS, target = AudioTarget.PARTICLE_SPAWN_RATE, smoothing = 0f)

        // Loud bass, silent mid: only the particle layer (bass-bound) should react.
        val loudBass = frame(bass = 1f, mid = 0f, treble = 0f)
        val imageValue = resolver.resolve("image-layer", imageBinding, loudBass)
        val particlesValue = resolver.resolve("particles-layer", particlesBinding, loudBass)

        assertEquals("image layer is mid-bound, should ignore bass", 0f, imageValue, 1e-4f)
        assertEquals("particles layer is bass-bound, should react to it", 1f, particlesValue, 1e-4f)
    }

    @Test
    fun `resolving one layer does not perturb another layer's smoothed state`() {
        val resolver = AudioBindingResolver()
        // Same band and smoothing on purpose: only per-layer id should keep their state apart.
        val binding = AudioBinding(band = AudioBand.BASS, target = AudioTarget.SCALE, smoothing = 0.8f)

        // Warm both layers up to steady state at bass=1.
        repeat(20) {
            resolver.resolve("layer-a", binding, frame(bass = 1f))
            resolver.resolve("layer-b", binding, frame(bass = 1f))
        }

        // Drive layer A down to bass=0 many times; layer B is never touched again.
        var layerA = 0f
        repeat(20) {
            layerA = resolver.resolve("layer-a", binding, frame(bass = 0f))
        }
        val layerB = resolver.resolve("layer-b", binding, frame(bass = 1f))

        assertTrue("layer A should have decayed toward 0, got $layerA", layerA < 0.1f)
        assertEquals("layer B's state must be unaffected by layer A's updates", 1f, layerB, 1e-3f)
    }

    @Test
    fun `no binding on one layer does not affect a bound sibling layer`() {
        val resolver = AudioBindingResolver()
        val boundBinding = AudioBinding(band = AudioBand.TREBLE, target = AudioTarget.EFFECT_INTENSITY, smoothing = 0f)
        val testFrame = frame(bass = 1f, mid = 1f, treble = 0.6f)

        val unboundLayerValue = resolver.resolve("static-image", null, testFrame)
        val boundLayerValue = resolver.resolve("logo-particles", boundBinding, testFrame)

        assertEquals(0f, unboundLayerValue, 1e-4f)
        assertEquals(0.6f, boundLayerValue, 1e-4f)
    }
}
