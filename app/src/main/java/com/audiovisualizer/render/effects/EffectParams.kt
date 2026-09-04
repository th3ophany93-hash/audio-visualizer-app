package com.audiovisualizer.render.effects

import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.BlendMode

/**
 * How a layer's animated intensity responds to audio. This is orthogonal to
 * [MovementParams] (which only ever drifts on its own ambient cycle) and to
 * [EffectParams.beatFlicker] (an independent hard on/off strobe that can be
 * combined with any of these).
 */
enum class ReactionMode {
    /** No audio influence and no ambient wander either: a constant, fully-on intensity. */
    NONE,

    /** Today's default for an unbound layer: intensity wanders on its own slow [AmbientCycle], audio-blind. */
    AMBIENT_ONLY,

    /** Today's [ClimaxDetector] behavior: ambient wander plus a rare, slow-fading boost during sustained climaxes. */
    SMOOTH_CLIMAX,

    /** Classic direct pulse-to-the-beat: a fast attack/release envelope that follows the chosen band closely. */
    BEAT_PULSE
}

/** One stop in a 1-3 stop color gradient. [position] is 0..1. */
data class ColorStop(val color: Int, val position: Float = 0f)

/** A solid color (one stop) or a 2-3 stop gradient, in ARGB int form. */
data class ColorSpec(val stops: List<ColorStop>) {
    init {
        require(stops.isNotEmpty()) { "ColorSpec needs at least one stop" }
        require(stops.size <= 3) { "ColorSpec supports at most 3 stops" }
    }

    /** The first stop's color - what any code that only understands a single color should use. */
    val primary: Int get() = stops[0].color

    companion object {
        fun solid(color: Int) = ColorSpec(listOf(ColorStop(color, 0f)))
    }
}

/**
 * Drift/movement configuration shared by every effect layer's own
 * [AmbientCycle]-driven wander (see [FogDriftAnimator] and glow's shader
 * drift). Disabled or default-valued, this reproduces exactly the fixed
 * constants those two used before movement became configurable.
 */
data class MovementParams(
    val enabled: Boolean = true,
    /** Multiplier on the effect's base drift speed. 1 = today's fixed speed. */
    val speed: Float = 1f,
    /** Extra heading bias, in radians, added on top of the ambient-driven direction. 0 = no change. */
    val direction: Float = 0f
)

/**
 * Numeric knobs for how a [ReactionMode] actually responds to the audio
 * signal - previously hardcoded constants inside [ClimaxDetector] and
 * [LayerAnimator]. Defaults reproduce today's exact [ReactionMode.SMOOTH_CLIMAX]
 * tuning.
 */
data class ReactionTuning(
    val band: AudioBand = AudioBand.BASS,
    /** Multiplies the raw band value before it feeds the reaction. */
    val sensitivity: Float = 1f,
    /** Subtracted as a noise-gate margin before the reaction can register. */
    val threshold: Float = 0f,
    /** Rise time. For SMOOTH_CLIMAX this is the boost's envelope rise; for BEAT_PULSE it's the pulse attack. */
    val attackSeconds: Float = 4f,
    /** Fall time, same role as [attackSeconds] but for decay. */
    val releaseSeconds: Float = 10f,
    /** Final remap of the combined 0..1 reaction value, same role as the old AudioBinding min/max. */
    val minIntensity: Float = 0f,
    val maxIntensity: Float = 1f
)

/**
 * The universal parameter layer every effect-bearing layer (particles, fog,
 * glow, chromatic aberration) is built from. Effect-specific extras live
 * alongside this in each `Effect.*` data class; this is everything that
 * applies the same way to all four.
 */
data class EffectParams(
    val enabled: Boolean = true,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val zone: SpawnZone = SpawnZone.FullScreen,
    val color: ColorSpec = ColorSpec.solid(0xFFFFFFFF.toInt()),
    val movement: MovementParams = MovementParams(),
    val reactionMode: ReactionMode = ReactionMode.AMBIENT_ONLY,
    /** Independent hard flicker/strobe synced to onsets - orthogonal to [reactionMode]. Off by default. */
    val beatFlicker: Boolean = false,
    val reactionTuning: ReactionTuning = ReactionTuning()
)
