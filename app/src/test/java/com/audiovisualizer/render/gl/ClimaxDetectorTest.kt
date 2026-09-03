package com.audiovisualizer.render.gl

import org.junit.Assert.assertTrue
import org.junit.Test

class ClimaxDetectorTest {

    private val dt = 1f / 30f

    @Test
    fun `a single short spike does not trigger a boost`() {
        val detector = ClimaxDetector()
        var maxBoost = 0f
        var t = 0f
        while (t < 10f) {
            val bandValue = if (t in 3f..3.1f) 1f else 0f
            maxBoost = maxOf(maxBoost, detector.update(dt, bandValue))
            t += dt
        }
        assertTrue("a single beat should never produce a visible boost, got $maxBoost", maxBoost < 0.1f)
    }

    @Test
    fun `a steady regular beat pattern settles back to no climax`() {
        // 120 BPM: a 100ms hit every 500ms, for long enough that both the
        // fast and slow averages fully settle to the same steady-state
        // level - a typical EDM beat with no actual change in the track's
        // energy over time.
        val detector = ClimaxDetector()
        var maxBoostOnceSettled = 0f
        var t = 0f
        while (t < 160f) {
            val phase = t % 0.5f
            val bandValue = if (phase < 0.1f) 1f else 0f
            val boost = detector.update(dt, bandValue)
            if (t > 120f) maxBoostOnceSettled = maxOf(maxBoostOnceSettled, boost)
            t += dt
        }
        assertTrue(
            "a steady beat with no real energy change should settle back to no climax, got $maxBoostOnceSettled",
            maxBoostOnceSettled < 0.1f
        )
    }

    @Test
    fun `sustained elevated energy after a quiet baseline produces a large boost`() {
        val detector = ClimaxDetector()
        var boost = 0f
        var t = 0f
        // Establish a quiet baseline first, like the start of a real track.
        while (t < 20f) {
            boost = detector.update(dt, 0.05f)
            t += dt
        }
        // Then a sustained loud section - a build paying off.
        t = 0f
        while (t < 15f) {
            boost = detector.update(dt, 1f)
            t += dt
        }
        assertTrue("expected a strong boost after 15s of sustained energy, got $boost", boost > 0.5f)
    }

    @Test
    fun `the boost fades out slowly rather than snapping to zero`() {
        val detector = ClimaxDetector()
        var boost = 0f
        var t = 0f
        while (t < 20f) {
            boost = detector.update(dt, 0.05f)
            t += dt
        }
        t = 0f
        while (t < 15f) {
            boost = detector.update(dt, 1f)
            t += dt
        }
        val boostAtClimax = boost
        assertTrue("expected a strong boost before the drop", boostAtClimax > 0.5f)

        // Cut back to the quiet baseline and check the boost one second later.
        t = 0f
        while (t < 1f) {
            boost = detector.update(dt, 0.05f)
            t += dt
        }
        assertTrue(
            "expected the boost to still be mostly present 1s after the climax ended " +
                "(was $boostAtClimax, now $boost) - a beat-synced effect would already be back to zero",
            boost > 0.5f
        )

        // But it should eventually fade all the way out.
        t = 0f
        while (t < 35f) {
            boost = detector.update(dt, 0.05f)
            t += dt
        }
        assertTrue("expected the boost to fully fade out eventually, got $boost", boost < 0.1f)
    }

    @Test
    fun `a genuine climax is still detected on top of an existing steady beat`() {
        val detector = ClimaxDetector()

        // Establish a normal, steady beat baseline.
        var t = 0f
        while (t < 20f) {
            val phase = t % 0.5f
            detector.update(dt, if (phase < 0.1f) 1f else 0f)
            t += dt
        }

        // Then the track genuinely gets louder and denser - simulated as
        // sustained near-maximum energy instead of the sparse pulses above.
        var boost = 0f
        t = 0f
        while (t < 15f) {
            boost = detector.update(dt, 1f)
            t += dt
        }
        assertTrue("expected a real climax on top of a steady beat to still register, got $boost", boost > 0.4f)
    }
}
