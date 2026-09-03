package com.audiovisualizer.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnsetPickerTest {

    @Test
    fun `sharp spike is detected as an onset`() {
        val flux = floatArrayOf(0.1f, 0.1f, 0.1f, 5.0f, 0.1f, 0.1f, 0.1f, 0.1f)
        val onsets = OnsetPicker.pick(flux, sensitivity = 1.5f, windowRadius = 3)

        assertTrue(onsets.isOnset[3])
        assertTrue(onsets.strength[3] > 0.5f)
        assertFalse(onsets.isOnset[0])
        assertFalse(onsets.isOnset[7])
    }

    @Test
    fun `flat flux produces no onsets`() {
        val flux = FloatArray(10) { 1f }
        val onsets = OnsetPicker.pick(flux, sensitivity = 1.5f)

        assertTrue(onsets.isOnset.none { it })
    }

    @Test
    fun `empty flux does not throw`() {
        val onsets = OnsetPicker.pick(FloatArray(0), sensitivity = 1.5f)
        assertTrue(onsets.isOnset.isEmpty())
    }
}
