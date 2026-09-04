package com.audiovisualizer.render.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolates [TextureLoader.applyExifOrientation] from the whole GL/UV
 * pipeline (see [ImageExifOrientationRenderTest] for the end-to-end
 * version) by checking [Bitmap.getPixel] directly - no EGL/MediaCodec
 * involved - so a wrong prediction about which way the bitmap rotates
 * can't be confused with a bug somewhere else in the render pipeline.
 */
@RunWith(AndroidJUnit4::class)
class BitmapExifOrientationTest {

    /** 4x4 bitmap, one solid color per quadrant: TL=RED, TR=GREEN, BL=BLUE, BR=YELLOW. */
    private fun quadrantBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, 2f, 2f, paint)
        paint.color = Color.GREEN
        canvas.drawRect(2f, 0f, 4f, 2f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(0f, 2f, 2f, 4f, paint)
        paint.color = Color.YELLOW
        canvas.drawRect(2f, 2f, 4f, 4f, paint)
        return bitmap
    }

    @Test
    fun rotate90PlacesEachRawCornerAtItsCorrectedPosition() {
        val corrected = TextureLoader.applyExifOrientation(quadrantBitmap(), ExifInterface.ORIENTATION_ROTATE_90)

        val topLeft = corrected.getPixel(0, 0)
        val topRight = corrected.getPixel(corrected.width - 1, 0)
        val bottomLeft = corrected.getPixel(0, corrected.height - 1)
        val bottomRight = corrected.getPixel(corrected.width - 1, corrected.height - 1)

        // Whatever this mapping actually is (verified here directly against
        // Bitmap.getPixel, with no GL/UV convention involved), it's the
        // ground truth the GL-pipeline test must match.
        assertEquals("top-left", Color.BLUE, topLeft)
        assertEquals("top-right", Color.RED, topRight)
        assertEquals("bottom-left", Color.YELLOW, bottomLeft)
        assertEquals("bottom-right", Color.GREEN, bottomRight)
    }
}
