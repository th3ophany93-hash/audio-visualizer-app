package com.audiovisualizer.render.gl

/**
 * Turns rare, sustained rises in a track's energy - a build paying off, a
 * drop, a chorus kicking in - into a slow-attack, slow-decay boost value.
 *
 * Tracks two plain rolling averages of the input at different speeds: a
 * "current" average (a handful of seconds - long enough to smooth out
 * individual beats, short enough to react to a real change quickly) and a
 * much slower "baseline" average (tens of seconds - what's normal for this
 * stretch of the track, beats included). A climax is `current` sitting
 * meaningfully above `baseline`; the [boost] output is a further
 * slow-attack, slow-decay envelope on top of that excess.
 *
 * A single beat barely nudges `current` and decays away before the
 * multi-second attack envelope can react. A *steady* beat pattern raises
 * both averages together until they settle at the same level (that's just
 * what the track normally sounds like), so its own excess fades back to
 * zero rather than reading as a permanent climax. Only a genuine, sustained
 * rise keeps `current` meaningfully ahead of the slower `baseline` for long
 * enough to build a real boost.
 */
class ClimaxDetector(
    private val currentWindowSeconds: Float = 6f,
    private val baselineWindowSeconds: Float = 35f,
    private val riseSeconds: Float = 4f,
    private val fallSeconds: Float = 10f,
    private val excessScale: Float = 4f,
    /** Subtracted from the current-vs-baseline excess before scaling - a noise-gate margin. 0 = old behavior. */
    private val threshold: Float = 0f
) {
    private var current = 0f
    private var baseline = 0f
    private var boost = 0f

    /** Call once per frame with the elapsed time and the current (0..1) band value. Returns the 0..1 boost. */
    fun update(deltaSeconds: Float, bandValue: Float): Float {
        if (deltaSeconds <= 0f) return boost

        val currentAlpha = (deltaSeconds / currentWindowSeconds).coerceIn(0f, 1f)
        val baselineAlpha = (deltaSeconds / baselineWindowSeconds).coerceIn(0f, 1f)
        current += (bandValue - current) * currentAlpha
        baseline += (bandValue - baseline) * baselineAlpha

        val excess = (current - baseline - threshold).coerceAtLeast(0f)
        val target = (excess * excessScale).coerceIn(0f, 1f)

        val envelopeSeconds = if (target > boost) riseSeconds else fallSeconds
        val alpha = (deltaSeconds / envelopeSeconds).coerceIn(0f, 1f)
        boost += (target - boost) * alpha

        return boost.coerceIn(0f, 1f)
    }
}
