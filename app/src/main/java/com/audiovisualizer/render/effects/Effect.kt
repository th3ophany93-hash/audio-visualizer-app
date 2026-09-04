package com.audiovisualizer.render.effects

sealed class SpawnZone {
    object FullScreen : SpawnZone()
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) : SpawnZone()
    data class Circle(val centerX: Float, val centerY: Float, val radius: Float) : SpawnZone()
}

/**
 * Effect-specific parameters only. Everything that applies the same way to
 * every effect type - enabled, opacity, blendMode, scale, zone, color,
 * movement, audio reaction - lives in [EffectParams] instead; a layer
 * carries one of these alongside its [EffectParams].
 */
sealed class Effect {
    data class Particles(
        val count: Int = 200,
        val size: Float = 4f,
        val speed: Float = 1f,
        val spawnRate: Float = 1f,
        val lifetime: Float = 2.5f,
        val gravity: Float = 0f
    ) : Effect()

    data class Fog(
        val density: Float = 0.5f,
        val noiseScale: Float = 1f,
        val driftSpeedX: Float = 0f,
        val driftSpeedY: Float = 0f
    ) : Effect()

    data class Glow(
        val intensity: Float = 1f,
        val radius: Float = 8f
    ) : Effect()

    data class ChromaticAberration(
        val strength: Float = 0.02f
    ) : Effect()
}
