package com.audiovisualizer.render.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES30
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiovisualizer.render.FitMode
import com.audiovisualizer.render.ImageTransform
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Regression for the "image loads upside-down/sideways" bug report: a real
 * photo's raw pixel data is often stored exactly as the sensor captured it,
 * with an EXIF orientation tag telling viewers how to rotate it for
 * display. [TextureLoader] used to ignore that tag entirely (plain
 * BitmapFactory.decodeStream), so any photo needing correction rendered
 * wrong. This writes a real JPEG with distinct-colored quadrants and a
 * ROTATE_90 EXIF tag, renders it through the real [LayerCompositor] with
 * FitMode.STRETCH (so screen pixels map 1:1 to the corrected bitmap, no
 * fit-mode cropping to account for), and checks the corners land where a
 * viewer honoring the EXIF tag would expect.
 */
@RunWith(AndroidJUnit4::class)
class ImageExifOrientationRenderTest {

    @Test
    fun rotate90ExifTagIsAppliedBeforeDisplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Raw (as-stored, pre-correction) quadrant layout:
        // top-left=RED, top-right=GREEN, bottom-left=BLUE, bottom-right=YELLOW.
        val imageUri = writeQuadrantJpegWithExifOrientation(context, ExifInterface.ORIENTATION_ROTATE_90)

        val width = 64
        val height = 64
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val eglSurface = EglRenderSurface(inputSurface)
        eglSurface.makeCurrent()

        val compositor = LayerCompositor(context)
        compositor.init()
        compositor.viewportChanged(width, height)

        val imageLayer = Layer(
            id = "exif-image",
            name = "Exif image",
            source = LayerSource.Image(imageUri, ImageTransform(fitMode = FitMode.STRETCH))
        )
        compositor.drawFrame(listOf(imageLayer), null, deltaSeconds = 0.05f)

        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)

        fun colorAt(px: Int, py: Int): Triple<Int, Int, Int> {
            // glReadPixels rows are bottom-to-top; py here is a top-origin screen row.
            val glRow = height - 1 - py
            val offset = (glRow * width + px) * 4
            val r = buffer.get(offset).toInt() and 0xFF
            val g = buffer.get(offset + 1).toInt() and 0xFF
            val b = buffer.get(offset + 2).toInt() and 0xFF
            return Triple(r, g, b)
        }

        // Sample well inside each screen quadrant, away from any edge blur.
        val screenTopLeft = colorAt(16, 16)
        val screenTopRight = colorAt(48, 16)
        val screenBottomLeft = colorAt(16, 48)
        val screenBottomRight = colorAt(48, 48)

        // Save what was actually rendered so it can be pulled and looked at directly.
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (r, g, b) = colorAt(x, y)
                outBitmap.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        File(context.getExternalFilesDir(null), "exif_orientation_render_proof.png").let { file ->
            FileOutputStream(file).use { out -> outBitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        outBitmap.recycle()

        compositor.release()
        eglSurface.release()
        encoder.stop()
        encoder.release()
        inputSurface.release()

        // ORIENTATION_ROTATE_90 means the raw pixels must be rotated 90 degrees
        // clockwise to display correctly: raw top-left(RED) -> displayed
        // top-right, raw top-right(GREEN) -> displayed bottom-right,
        // raw bottom-right(YELLOW) -> displayed bottom-left, raw
        // bottom-left(BLUE) -> displayed top-left.
        assertClose("top-left should show the raw bottom-left color (BLUE) once EXIF-corrected", screenTopLeft, Triple(0, 0, 255))
        assertClose("top-right should show the raw top-left color (RED) once EXIF-corrected", screenTopRight, Triple(255, 0, 0))
        assertClose("bottom-left should show the raw bottom-right color (YELLOW) once EXIF-corrected", screenBottomLeft, Triple(255, 255, 0))
        assertClose("bottom-right should show the raw top-right color (GREEN) once EXIF-corrected", screenBottomRight, Triple(0, 255, 0))
    }

    private fun assertClose(message: String, actual: Triple<Int, Int, Int>, expected: Triple<Int, Int, Int>) {
        val distance = kotlin.math.abs(actual.first - expected.first) +
            kotlin.math.abs(actual.second - expected.second) +
            kotlin.math.abs(actual.third - expected.third)
        assertTrue("$message (expected ~$expected, got $actual, distance=$distance)", distance < 90)
    }

    /** A 64x64 JPEG with four solid-color quadrants (raw, pre-EXIF-correction layout) and the given EXIF orientation tag. */
    private fun writeQuadrantJpegWithExifOrientation(context: android.content.Context, orientation: Int): Uri {
        val size = 64
        val half = size / 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, half.toFloat(), half.toFloat(), paint) // top-left
        paint.color = Color.GREEN
        canvas.drawRect(half.toFloat(), 0f, size.toFloat(), half.toFloat(), paint) // top-right
        paint.color = Color.BLUE
        canvas.drawRect(0f, half.toFloat(), half.toFloat(), size.toFloat(), paint) // bottom-left
        paint.color = Color.YELLOW
        canvas.drawRect(half.toFloat(), half.toFloat(), size.toFloat(), size.toFloat(), paint) // bottom-right

        val file = File(context.cacheDir, "exif_quadrant_test.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
        bitmap.recycle()

        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()

        return Uri.fromFile(file)
    }
}
