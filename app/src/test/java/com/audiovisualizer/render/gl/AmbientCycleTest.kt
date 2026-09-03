package com.audiovisualizer.render.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientCycleTest {

    @Test
    fun `value always stays within 0 to 1`() {
        val cycle = AmbientCycle(seed = 1L)
        var t = 0f
        while (t < 120f) {
            val v = cycle.value(t)
            assertTrue("value $v at t=$t out of range", v in 0f..1f)
            t += 0.5f
        }
    }

    @Test
    fun `the value actually changes over time without any audio input`() {
        val cycle = AmbientCycle(seed = 2L)
        val samples = (0 until 60).map { cycle.value(it * 2f) }
        assertTrue("expected the ambient value to vary over a full 2 minutes", samples.distinct().size > 5)
    }

    @Test
    fun `different seeds produce different sequences so layers never move in lockstep`() {
        val a = AmbientCycle(seed = 10L)
        val b = AmbientCycle(seed = 20L)

        var anyDifferent = false
        var t = 0f
        while (t < 60f) {
            if (kotlin.math.abs(a.value(t) - b.value(t)) > 0.05f) {
                anyDifferent = true
                break
            }
            t += 1f
        }
        assertTrue("expected two independently-seeded ambient cycles to diverge", anyDifferent)
    }

    @Test
    fun `the same seed and time always reproduce the same value`() {
        val a = AmbientCycle(seed = 42L)
        val b = AmbientCycle(seed = 42L)
        assertEquals(a.value(37.5f), b.value(37.5f), 1e-6f)
    }
}
