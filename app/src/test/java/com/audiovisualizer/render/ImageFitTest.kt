package com.audiovisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFitTest {

    private val eps = 1e-4f

    @Test
    fun `STRETCH is always a no-op regardless of aspect ratio`() {
        val (sx, sy) = ImageFit.scale(imageWidth = 1000, imageHeight = 200, viewportWidth = 480, viewportHeight = 854, fitMode = FitMode.STRETCH)
        assertEquals(1f, sx, eps)
        assertEquals(1f, sy, eps)
    }

    @Test
    fun `FIT on a wide image in a tall viewport letterboxes vertically (full width, shorter height)`() {
        // Image proportionally wider than the viewport - fitting it means using the full width
        // and leaving bars above/below.
        val (sx, sy) = ImageFit.scale(imageWidth = 1600, imageHeight = 900, viewportWidth = 480, viewportHeight = 854, fitMode = FitMode.FIT)
        assertEquals(1f, sx, eps)
        assertTrue("expected the height to be scaled down to letterbox, got $sy", sy < 1f)
    }

    @Test
    fun `FIT on a tall image in a wide viewport letterboxes horizontally (full height, narrower width)`() {
        val (sx, sy) = ImageFit.scale(imageWidth = 900, imageHeight = 1600, viewportWidth = 854, viewportHeight = 480, fitMode = FitMode.FIT)
        assertEquals(1f, sy, eps)
        assertTrue("expected the width to be scaled down to letterbox, got $sx", sx < 1f)
    }

    @Test
    fun `FILL on a wide image in a tall viewport covers height and crops width`() {
        // Cover: opposite of FIT - the shorter axis is pinned to fully cover, the other overflows (crops).
        val (sx, sy) = ImageFit.scale(imageWidth = 1600, imageHeight = 900, viewportWidth = 480, viewportHeight = 854, fitMode = FitMode.FILL)
        assertEquals(1f, sy, eps)
        assertTrue("expected the width to overflow (crop) to cover the viewport, got $sx", sx > 1f)
    }

    @Test
    fun `FILL on a tall image in a wide viewport covers width and crops height`() {
        val (sx, sy) = ImageFit.scale(imageWidth = 900, imageHeight = 1600, viewportWidth = 854, viewportHeight = 480, fitMode = FitMode.FILL)
        assertEquals(1f, sx, eps)
        assertTrue("expected the height to overflow (crop) to cover the viewport, got $sy", sy > 1f)
    }

    @Test
    fun `matching aspect ratios need no letterboxing or cropping for either FIT or FILL`() {
        val fit = ImageFit.scale(imageWidth = 480, imageHeight = 854, viewportWidth = 480, viewportHeight = 854, fitMode = FitMode.FIT)
        val fill = ImageFit.scale(imageWidth = 480, imageHeight = 854, viewportWidth = 480, viewportHeight = 854, fitMode = FitMode.FILL)
        assertEquals(1f, fit.first, eps)
        assertEquals(1f, fit.second, eps)
        assertEquals(1f, fill.first, eps)
        assertEquals(1f, fill.second, eps)
    }

    @Test
    fun `degenerate zero-sized inputs fall back to a no-op instead of dividing by zero`() {
        val result = ImageFit.scale(imageWidth = 0, imageHeight = 0, viewportWidth = 480, viewportHeight = 854, fitMode = FitMode.FILL)
        assertEquals(1f, result.first, eps)
        assertEquals(1f, result.second, eps)
    }
}
