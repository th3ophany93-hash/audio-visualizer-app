package com.audiovisualizer.render.effects

/** Where an area-based effect (particles, fog) is emitted from on screen, in normalized 0..1 coordinates. */
sealed class SpawnZone {
    object FullScreen : SpawnZone()
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) : SpawnZone()
    data class Circle(val centerX: Float, val centerY: Float, val radius: Float) : SpawnZone()
}

/** Parameters for one of the base GLSL effects, applied on top of a [com.audiovisualizer.render.Layer]. */
sealed class Effect {

    data class Particles(
        val count: Int = 200,
        val size: Float = 4f,
        val speed: Float = 1f,
        val color: Int = 0xFFFFFFFF.toInt(),
        val spawnZone: SpawnZone = SpawnZone.FullScreen
    ) : Effect()

    data class Fog(
        val density: Float = 0.5f,
        val zone: SpawnZone = SpawnZone.FullScreen,
        val color: Int = 0x80FFFFFF.toInt()
    ) : Effect()

    data class Glow(
        val intensity: Float = 1f,
        val radius: Float = 8f
    ) : Effect()

    data class ChromaticAberration(
        val strength: Float = 0.02f
    ) : Effect()
}
