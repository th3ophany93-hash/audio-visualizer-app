package com.audiovisualizer.render.gl

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.render.effects.ColorSpec
import com.audiovisualizer.render.effects.Effect
import com.audiovisualizer.render.effects.EffectParams
import com.audiovisualizer.render.effects.ReactionMode
import com.audiovisualizer.render.effects.ReactionTuning
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Proves [LayerCompositor] renders correctly into a MediaCodec video
 * encoder's input surface - what [com.audiovisualizer.export.MediaCodecVideoExporter]
 * actually draws into - independent of encoding, muxing and multi-frame
 * timing: draws one image layer and one particle layer for a single frame,
 * then reads the framebuffer straight back with glReadPixels.
 *
 * H.264-encoded output on this project's software-emulated test devices
 * cannot be reliably decoded back for pixel inspection (MediaMetadataRetriever
 * and ImageReader-based decode both hit emulator/software-decoder quirks
 * unrelated to app correctness), so this test checks the same GL surface the
 * encoder consumes directly instead of round-tripping through the codec.
 */
@RunWith(AndroidJUnit4::class)
class LayerCompositorExportRenderTest {

    @Test
    fun drawsImageAndParticleLayersIntoEncoderInputSurface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val backgroundColor = Color.rgb(30, 20, 90)
        val imageFile = File(context.cacheDir, "compositor_export_bg.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(backgroundColor)
        }.let { bitmap -> FileOutputStream(imageFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val imageUri = Uri.fromFile(imageFile)

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

        val imageLayer = Layer(id = "image", name = "Background", source = LayerSource.Image(imageUri))
        val particleLayer = Layer(
            id = "particles",
            name = "Particles",
            blendMode = BlendMode.ADD,
            source = LayerSource.Particles(Effect.Particles(count = 200, size = 16f, speed = 1.5f)),
            effectParams = EffectParams(
                color = ColorSpec.solid(0xFFFFFFFF.toInt()),
                reactionMode = ReactionMode.SMOOTH_CLIMAX,
                reactionTuning = ReactionTuning(band = AudioBand.BASS, sensitivity = 2f)
            )
        )
        compositor.drawFrame(listOf(imageLayer, particleLayer), null, deltaSeconds = 0.05f)

        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)

        // Particles now always carry some ambient motion of their own (the
        // whole point of this task), so no fixed corner is guaranteed to be
        // particle-free the way it would be with the old "zero without
        // audio" behavior. Scan 8x8 blocks across the whole frame instead
        // and require at least one that's close to the background color,
        // and at least one bright block for the (ADD-blended, white)
        // particles - proving both layers actually drew, without assuming
        // where on screen either one landed.
        var closestToBackground = Int.MAX_VALUE
        var brightestBlock = 0
        val blockSize = 8
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var sampleCount = 0
                for (by in 0 until blockSize) {
                    for (bx in 0 until blockSize) {
                        val offset = ((y + by) * width + (x + bx)) * 4
                        sumR += buffer.get(offset).toInt() and 0xFF
                        sumG += buffer.get(offset + 1).toInt() and 0xFF
                        sumB += buffer.get(offset + 2).toInt() and 0xFF
                        sampleCount++
                    }
                }
                val r = sumR / sampleCount
                val g = sumG / sampleCount
                val b = sumB / sampleCount

                val distanceToBackground = kotlin.math.abs(r - Color.red(backgroundColor)) +
                    kotlin.math.abs(g - Color.green(backgroundColor)) +
                    kotlin.math.abs(b - Color.blue(backgroundColor))
                closestToBackground = minOf(closestToBackground, distanceToBackground)
                brightestBlock = maxOf(brightestBlock, r + g + b)

                x += blockSize
            }
            y += blockSize
        }

        compositor.release()
        eglSurface.release()
        encoder.stop()
        encoder.release()
        inputSurface.release()

        assertTrue(
            "expected some block close to the background image's color " +
                "(${Color.red(backgroundColor)},${Color.green(backgroundColor)},${Color.blue(backgroundColor)}), " +
                "closest distance was $closestToBackground",
            closestToBackground < 40
        )
        assertTrue(
            "expected some bright block from the white, ADD-blended particles, brightest sum was $brightestBlock",
            brightestBlock > 400
        )
    }
}
