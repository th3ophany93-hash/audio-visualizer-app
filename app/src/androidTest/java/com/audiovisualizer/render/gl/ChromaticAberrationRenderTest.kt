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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiovisualizer.render.FitMode
import com.audiovisualizer.render.ImageTransform
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.render.effects.Effect
import com.audiovisualizer.render.effects.EffectParams
import com.audiovisualizer.render.effects.ReactionMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bug report: chromatic aberration is invisible in the live app no matter
 * the blend mode/intensity/beat-reaction/sensitivity tried. Renders a sharp
 * black/white vertical edge (a flat-colored image shows no fringing at all,
 * since offsetting the R/B sample position of a *uniform* color still reads
 * that same color back - a real edge is required to see anything) once
 * without a [LayerSource.ChromaticAberration] layer and once with one at
 * max UI strength (0.2) and [ReactionMode.NONE] (so intensity is a
 * deterministic 1, no ambient-wander timing flakiness), through the real
 * [LayerCompositor] FBO ping-pong pipeline. Saves both frames as PNGs so the
 * effect can be seen directly, not just asserted about.
 */
@RunWith(AndroidJUnit4::class)
class ChromaticAberrationRenderTest {

    @Test
    fun chromaticAberrationLayerVisiblyFringesAHardEdge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val imageUri = writeHardEdgeImage(context)

        val width = 128
        val height = 128

        val baseline = renderFrame(context, imageUri, width, height) { imageLayer -> listOf(imageLayer) }
        val withAberration = renderFrame(context, imageUri, width, height) { imageLayer ->
            val aberrationLayer = Layer(
                id = "chromatic-aberration",
                name = "Chromatic Aberration",
                source = LayerSource.ChromaticAberration(Effect.ChromaticAberration(strength = 0.2f)),
                effectParams = EffectParams(reactionMode = ReactionMode.NONE)
            )
            listOf(imageLayer, aberrationLayer)
        }

        saveProofPng(context, baseline, width, height, "chromatic_aberration_baseline.png")
        saveProofPng(context, withAberration, width, height, "chromatic_aberration_applied.png")

        // Sample a strip straddling the hard edge. The edge sits at x=32 (not
        // screen-center x=64): chromatic aberration's sample offset radiates
        // from the frame's center, so a boundary placed exactly on the
        // center line would see zero *horizontal* offset (only vertical, to
        // which a vertical edge is blind) and wrongly look unaffected - the
        // edge must be off-center for the horizontal fringing to show at all.
        var maxChannelDelta = 0
        var totalDelta = 0L
        for (y in 40 until 88) {
            for (x in 24 until 40) {
                val base = colorAt(baseline, width, height, x, y)
                val aberrated = colorAt(withAberration, width, height, x, y)
                val dr = kotlin.math.abs(base.first - aberrated.first)
                val dg = kotlin.math.abs(base.second - aberrated.second)
                val db = kotlin.math.abs(base.third - aberrated.third)
                maxChannelDelta = maxOf(maxChannelDelta, dr, dg, db)
                totalDelta += dr + dg + db
            }
        }

        imageUri.path?.let { File(it).delete() }

        assertTrue(
            "expected chromatic aberration to visibly shift color near the hard edge " +
                "(max single-channel delta was only $maxChannelDelta, total delta $totalDelta) - " +
                "see chromatic_aberration_baseline.png vs chromatic_aberration_applied.png",
            maxChannelDelta > 60
        )
    }

    /**
     * The likely real cause behind "invisible no matter the settings": a
     * chromatic aberration layer only distorts whatever is already drawn
     * below it in the stack (documented, correct post-process behavior -
     * see [LayerCompositor]'s class doc), so a layer added *before* any
     * background/content layer has nothing to distort and looks identical
     * to no effect at all, regardless of strength/blend/reaction settings.
     * This is exactly why [LayerListAdapter] grew move-up/move-down
     * controls: this test proves the same layer, same max strength, is
     * invisible when ordered first and clearly visible once moved after
     * the image - a real ordering bug, not a rendering one.
     */
    @Test
    fun aberrationLayerOrderedBeforeItsBackgroundHasNothingToDistort() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val imageUri = writeHardEdgeImage(context)
        val width = 128
        val height = 128

        fun aberrationLayer() = Layer(
            id = "chromatic-aberration",
            name = "Chromatic Aberration",
            source = LayerSource.ChromaticAberration(Effect.ChromaticAberration(strength = 0.2f)),
            effectParams = EffectParams(reactionMode = ReactionMode.NONE)
        )

        val orderedFirst = renderFrame(context, imageUri, width, height) { imageLayer ->
            listOf(aberrationLayer(), imageLayer)
        }
        val orderedAfter = renderFrame(context, imageUri, width, height) { imageLayer ->
            listOf(imageLayer, aberrationLayer())
        }
        val baseline = renderFrame(context, imageUri, width, height) { imageLayer -> listOf(imageLayer) }

        // Direct comparison: the "ordered first" render must match the
        // no-aberration-at-all baseline (nothing below it to distort);
        // "ordered after" must match the already-proven fringed render.
        var deltaFirstVsBaseline = 0
        var deltaAfterVsBaseline = 0
        for (y in 40 until 88) {
            for (x in 24 until 40) {
                val a = colorAt(orderedFirst, width, height, x, y)
                val b = colorAt(baseline, width, height, x, y)
                deltaFirstVsBaseline = maxOf(deltaFirstVsBaseline, kotlin.math.abs(a.first - b.first))
                val c = colorAt(orderedAfter, width, height, x, y)
                deltaAfterVsBaseline = maxOf(deltaAfterVsBaseline, kotlin.math.abs(c.first - b.first))
            }
        }

        assertTrue(
            "a chromatic aberration layer ordered BEFORE its background should render " +
                "identically to no aberration at all (nothing below it to distort), but differed by $deltaFirstVsBaseline",
            deltaFirstVsBaseline < 5
        )
        assertTrue(
            "the same layer ordered AFTER its background should visibly fringe the edge " +
                "(delta vs baseline was only $deltaAfterVsBaseline)",
            deltaAfterVsBaseline > 60
        )
    }

    private fun renderFrame(
        context: android.content.Context,
        imageUri: Uri,
        width: Int,
        height: Int,
        buildLayers: (Layer) -> List<Layer>
    ): ByteBuffer {
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
            id = "background",
            name = "Background",
            source = LayerSource.Image(imageUri, ImageTransform(fitMode = FitMode.STRETCH))
        )
        compositor.drawFrame(buildLayers(imageLayer), null, deltaSeconds = 0.05f)

        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)

        compositor.release()
        eglSurface.release()
        encoder.stop()
        encoder.release()
        inputSurface.release()

        return buffer
    }

    private fun colorAt(buffer: ByteBuffer, width: Int, height: Int, px: Int, py: Int): Triple<Int, Int, Int> {
        val glRow = height - 1 - py
        val offset = (glRow * width + px) * 4
        val r = buffer.get(offset).toInt() and 0xFF
        val g = buffer.get(offset + 1).toInt() and 0xFF
        val b = buffer.get(offset + 2).toInt() and 0xFF
        return Triple(r, g, b)
    }

    private fun saveProofPng(context: android.content.Context, buffer: ByteBuffer, width: Int, height: Int, name: String) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (r, g, b) = colorAt(buffer, width, height, x, y)
                bitmap.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        File(context.getExternalFilesDir(null), name).let { file ->
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        bitmap.recycle()
    }

    /**
     * A 128x128 image, black up to x=32 and white after - an edge deliberately
     * off the image's horizontal center (x=64), so the radial chromatic
     * aberration offset has a nonzero horizontal component there.
     */
    private fun writeHardEdgeImage(context: android.content.Context): Uri {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.BLACK
        canvas.drawRect(0f, 0f, 32f, size.toFloat(), paint)
        paint.color = Color.WHITE
        canvas.drawRect(32f, 0f, size.toFloat(), size.toFloat(), paint)

        val file = File(context.cacheDir, "chromatic_aberration_hard_edge.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
}
