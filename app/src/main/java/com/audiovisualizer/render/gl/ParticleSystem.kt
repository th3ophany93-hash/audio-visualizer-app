package com.audiovisualizer.render.gl

import com.audiovisualizer.render.effects.Effect
import com.audiovisualizer.render.effects.SpawnZone
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CPU-side simulation for an [Effect.Particles] layer: spawns, ages and
 * moves points in normalized device coordinates each frame. [update]'s
 * [intensity] (0..1, typically a resolved [com.audiovisualizer.render.AudioBinding])
 * scales spawn rate and speed so the effect visibly reacts to the music.
 */
class ParticleSystem(private val maxParticles: Int) {

    private class Particle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var life = 0f // 1 = just spawned, 0 = dead
    }

    private val particles = Array(maxParticles) { Particle() }
    private val random = Random(System.nanoTime())

    // Packed x, y, life per live particle - reused across frames to avoid per-frame allocation.
    private val vertexData = FloatArray(maxParticles * 3)
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    var activeCount: Int = 0
        private set

    fun update(deltaSeconds: Float, params: Effect.Particles, intensity: Float) {
        val clampedIntensity = intensity.coerceIn(0f, 1f)
        var toSpawn = params.count * (BASELINE_SPAWN_FRACTION + clampedIntensity) * deltaSeconds

        for (particle in particles) {
            var isDead = particle.life <= 0f
            if (!isDead) {
                particle.x += particle.vx * deltaSeconds
                particle.y += particle.vy * deltaSeconds
                particle.life -= deltaSeconds / LIFETIME_SECONDS
                if (particle.life < 0f) particle.life = 0f
                // A particle whose life just ran out is eligible for
                // respawn in this same pass. Without this, a particle can
                // only be reused starting next frame (dead-this-frame still
                // hits the "alive" branch above). Since every particle
                // spawned during the pool's initial fill-up is born within
                // the same short window, they'd all cross zero life
                // together ~LIFETIME_SECONDS later; deferring their reuse
                // by exactly one frame reproduces that same synchronized
                // gap every cycle, which reads as the whole layer visibly
                // pulsing at a ~LIFETIME_SECONDS period - independent of
                // whatever the (smoothly-varying) intensity is doing.
                isDead = particle.life <= 0f
            }
            if (isDead && toSpawn >= 1f) {
                spawn(particle, params, clampedIntensity)
                toSpawn -= 1f
            }
        }
    }

    private fun spawn(particle: Particle, params: Effect.Particles, intensity: Float) {
        val (spawnX, spawnY) = randomPointIn(params.spawnZone)
        particle.x = spawnX
        particle.y = spawnY
        val angle = random.nextFloat() * (2 * PI).toFloat()
        val speed = params.speed * (0.5f + intensity) * BASE_SPEED
        particle.vx = cos(angle) * speed
        particle.vy = sin(angle) * speed
        particle.life = 1f
    }

    private fun randomPointIn(zone: SpawnZone): Pair<Float, Float> = when (zone) {
        is SpawnZone.FullScreen -> (random.nextFloat() * 2f - 1f) to (random.nextFloat() * 2f - 1f)
        is SpawnZone.Rect -> {
            val x = (zone.x + random.nextFloat() * zone.width) * 2f - 1f
            val y = (zone.y + random.nextFloat() * zone.height) * 2f - 1f
            x to y
        }
        is SpawnZone.Circle -> {
            val angle = random.nextFloat() * (2 * PI).toFloat()
            val radius = zone.radius * sqrt(random.nextFloat())
            val x = (zone.centerX + cos(angle) * radius) * 2f - 1f
            val y = (zone.centerY + sin(angle) * radius) * 2f - 1f
            x to y
        }
    }

    /** Packs live particle state into a direct buffer ready to upload as vertex attributes. */
    fun toVertexBuffer(): FloatBuffer {
        var count = 0
        for (particle in particles) {
            if (particle.life <= 0f) continue
            vertexData[count * 3] = particle.x
            vertexData[count * 3 + 1] = particle.y
            vertexData[count * 3 + 2] = particle.life
            count++
        }
        vertexBuffer.clear()
        vertexBuffer.put(vertexData, 0, count * 3)
        vertexBuffer.position(0)
        activeCount = count
        return vertexBuffer
    }

    private companion object {
        const val LIFETIME_SECONDS = 2.5f
        const val BASE_SPEED = 0.5f
        const val BASELINE_SPAWN_FRACTION = 0.2f
    }
}
