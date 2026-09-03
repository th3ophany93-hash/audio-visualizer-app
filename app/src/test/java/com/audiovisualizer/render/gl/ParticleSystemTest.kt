package com.audiovisualizer.render.gl

import com.audiovisualizer.render.effects.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleSystemTest {

    @Test
    fun `higher intensity spawns more particles over the same time`() {
        val params = Effect.Particles(count = 200, size = 4f, speed = 1f)

        val quiet = ParticleSystem(maxParticles = 200)
        quiet.update(deltaSeconds = 0.5f, params = params, intensity = 0f)
        quiet.toVertexBuffer()

        val loud = ParticleSystem(maxParticles = 200)
        loud.update(deltaSeconds = 0.5f, params = params, intensity = 1f)
        loud.toVertexBuffer()

        assertTrue(
            "expected more particles at high intensity (${loud.activeCount}) than low (${quiet.activeCount})",
            loud.activeCount > quiet.activeCount
        )
    }

    @Test
    fun `zero intensity still spawns a baseline drizzle`() {
        val system = ParticleSystem(maxParticles = 100)
        system.update(deltaSeconds = 1f, params = Effect.Particles(count = 100), intensity = 0f)
        system.toVertexBuffer()

        assertTrue("expected some baseline particles even with silence", system.activeCount > 0)
    }

    @Test
    fun `particles age out and vertex buffer only contains live ones`() {
        val system = ParticleSystem(maxParticles = 50)
        system.update(deltaSeconds = 1f, params = Effect.Particles(count = 50), intensity = 1f)
        system.toVertexBuffer()
        val spawned = system.activeCount
        assertTrue(spawned > 0)

        // Advance well past the particle lifetime (2.5s) with no new spawns.
        repeat(10) {
            system.update(deltaSeconds = 1f, params = Effect.Particles(count = 0), intensity = 0f)
        }
        system.toVertexBuffer()

        assertEquals(0, system.activeCount)
    }
}
