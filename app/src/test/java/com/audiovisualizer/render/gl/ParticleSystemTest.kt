package com.audiovisualizer.render.gl

import com.audiovisualizer.render.effects.Effect
import com.audiovisualizer.render.effects.SpawnZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleSystemTest {

    private val fullScreen = SpawnZone.FullScreen

    @Test
    fun `higher intensity spawns more particles over the same time`() {
        val params = Effect.Particles(count = 200, size = 4f, speed = 1f)

        val quiet = ParticleSystem(maxParticles = 200)
        quiet.update(deltaSeconds = 0.5f, params = params, zone = fullScreen, intensity = 0f)
        quiet.toVertexBuffer()

        val loud = ParticleSystem(maxParticles = 200)
        loud.update(deltaSeconds = 0.5f, params = params, zone = fullScreen, intensity = 1f)
        loud.toVertexBuffer()

        assertTrue(
            "expected more particles at high intensity (${loud.activeCount}) than low (${quiet.activeCount})",
            loud.activeCount > quiet.activeCount
        )
    }

    @Test
    fun `zero intensity still spawns a baseline drizzle`() {
        val system = ParticleSystem(maxParticles = 100)
        system.update(deltaSeconds = 1f, params = Effect.Particles(count = 100), zone = fullScreen, intensity = 0f)
        system.toVertexBuffer()

        assertTrue("expected some baseline particles even with silence", system.activeCount > 0)
    }

    @Test
    fun `particles age out and vertex buffer only contains live ones`() {
        val system = ParticleSystem(maxParticles = 50)
        system.update(deltaSeconds = 1f, params = Effect.Particles(count = 50), zone = fullScreen, intensity = 1f)
        system.toVertexBuffer()
        val spawned = system.activeCount
        assertTrue(spawned > 0)

        // Advance well past the particle lifetime (2.5s default) with no new spawns.
        repeat(10) {
            system.update(deltaSeconds = 1f, params = Effect.Particles(count = 0), zone = fullScreen, intensity = 0f)
        }
        system.toVertexBuffer()

        assertEquals(0, system.activeCount)
    }

    @Test
    fun `active particle count does not oscillate under constant intensity`() {
        // A regression test for a cohort-resonance bug: every particle
        // spawned during the pool's initial fill-up was born in the same
        // short window, so without a same-frame respawn they'd all cross
        // zero life together roughly one lifetime later, then keep doing so
        // every cycle - the whole layer visibly pulsing at ~lifetime (2.5s)
        // regardless of how smoothly the driving intensity behaves.
        // intensity=0 keeps the steady-state population (count * 0.2 baseline
        // fraction * lifetime = 100 * 0.2 * 2.5 = 50) comfortably below the
        // 100-particle cap, so this also exercises the below-capacity case
        // (not just the pool sitting pegged at its ceiling).
        val params = Effect.Particles(count = 100)
        val system = ParticleSystem(maxParticles = 100)
        val dt = 1f / 20f

        val counts = mutableListOf<Int>()
        var t = 0f
        while (t < 15f) {
            system.update(deltaSeconds = dt, params = params, zone = fullScreen, intensity = 0f)
            system.toVertexBuffer()
            if (t > 4f) counts.add(system.activeCount) // past the initial fill-up transient
            t += dt
        }

        val min = counts.min()
        val max = counts.max()
        assertTrue(
            "expected activeCount to stay roughly steady under constant intensity, " +
                "got min=$min max=$max (counts=$counts)",
            max - min <= 2
        )
    }

    @Test
    fun `lifetime parameter changes how long particles survive`() {
        val shortLived = ParticleSystem(maxParticles = 50)
        shortLived.update(deltaSeconds = 1f, params = Effect.Particles(count = 50, lifetime = 0.5f), zone = fullScreen, intensity = 1f)
        shortLived.toVertexBuffer()
        assertTrue(shortLived.activeCount > 0)
        shortLived.update(deltaSeconds = 0.6f, params = Effect.Particles(count = 0, lifetime = 0.5f), zone = fullScreen, intensity = 0f)
        shortLived.toVertexBuffer()
        assertEquals(0, shortLived.activeCount)
    }

    @Test
    fun `gravity pulls particles downward over time`() {
        val system = ParticleSystem(maxParticles = 1)
        val spawnParams = Effect.Particles(count = 1, speed = 0f, gravity = 5f, lifetime = 10f)
        val zone = SpawnZone.Circle(0.5f, 0.5f, 0.001f)
        // First call spawns the particle (no time to fall yet); the second
        // call ages it with no new spawns so gravity is the only thing
        // moving it.
        system.update(deltaSeconds = 1f, params = spawnParams, zone = zone, intensity = 1f)
        system.update(deltaSeconds = 1f, params = spawnParams.copy(count = 0), zone = zone, intensity = 0f)
        val buffer = system.toVertexBuffer()
        assertTrue("expected at least one particle", system.activeCount > 0)
        val y = buffer.get(1)
        assertTrue("expected gravity to have pulled the particle's y down from spawn, got y=$y", y < 0f)
    }
}
