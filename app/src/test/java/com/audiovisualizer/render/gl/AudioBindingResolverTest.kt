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
}
