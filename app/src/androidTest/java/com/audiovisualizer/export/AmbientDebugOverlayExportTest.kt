package com.audiovisualizer.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.AudioBinding
import com.audiovisualizer.render.AudioTarget
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.render.effects.Effect
import com.audiovisualizer.render.effects.SpawnZone
import com.audiovisualizer.render.gl.EglRenderSurface
import com.audiovisualizer.render.gl.LayerAnimator
import com.audiovisualizer.render.gl.LayerCompositor
import com.audiovisualizer.render.gl.QuadMesh
import com.audiovisualizer.render.gl.ShaderLoader
import com.audiovisualizer.render.gl.ShaderProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a debug clip with a text overlay printing each ambient layer's
 * [LayerAnimator]-computed numbers (its AmbientCycle's dominant period and
 * phase, plus the current per-frame intensity) directly on top of the
 * frame, so the on-screen motion of particles vs. fog can be checked
 * against the actual numbers driving them, frame by frame. No audio is
 * used at all here (every AudioBinding sees a null frame) - this isolates
 * the ambient-only baseline behavior the original bug report was about.
 */
@RunWith(AndroidJUnit4::class)
class AmbientDebugOverlayExportTest {

    @Test
    fun rendersDebugOverlayAndParticlesTrackAmbientNotFastOscillation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val imageUri = writeTestImage(context)
        val particleLayer = Layer(
            id = "particles",
            name = "Particles",
            blendMode = BlendMode.ADD,
            source = LayerSource.Particles(
                Effect.Particles(count = 150, size = 10f, speed = 1.2f, color = 0xFFFFFFFF.toInt())
            ),
            audioBinding = AudioBinding(band = AudioBand.BASS, target = AudioTarget.PARTICLE_SPAWN_RATE)
        )
        val fogLayer = Layer(
            id = "fog",
            name = "Fog",
            blendMode = BlendMode.NORMAL,
            source = LayerSource.Fog(
                Effect.Fog(density = 0.6f, zone = SpawnZone.FullScreen, color = Color.argb(200, 130, 90, 220))
            ),
            audioBinding = AudioBinding(band = AudioBand.BASS, target = AudioTarget.EFFECT_INTENSITY)
        )
        val imageLayer = Layer(id = "background", name = "Background", source = LayerSource.Image(imageUri))
        val layers = listOf(imageLayer, fogLayer, particleLayer)

        val width = 360
        val height = 640
        val frameRate = 15
        val durationSeconds = 16f
        val totalFrames = (durationSeconds * frameRate).toInt()

        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
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

        // A second, independent LayerAnimator computing the exact same
        // numbers the compositor's internal one does (same layer ids, same
        // deltaSeconds and audio frame each call), purely so this test can
        // read them out for the overlay text without needing to expose
        // LayerCompositor's private state.
        val debugAnimator = LayerAnimator()

        val overlay = TextOverlayRenderer(context, width, overlayHeight = 110)

        val outputFile = File(context.getExternalFilesDir(null), "ambient_debug_overlay.mp4")
        outputFile.delete()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var videoTrackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()

        fun drainVideo(endOfStream: Boolean) {
            while (true) {
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outputIndex >= 0 -> {
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (bufferInfo.size > 0 && !isConfig && muxerStarted) {
                            val buffer = encoder.getOutputBuffer(outputIndex)!!
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                        }
                        val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if (isEos) return
                    }
                }
            }
        }

        val frameDurationNanos = 1_000_000_000L / frameRate
        var frameIndex = 0
        while (frameIndex < totalFrames) {
            val dt = 1f / frameRate

            compositor.drawFrame(layers, null, dt)

            val particlesResult = debugAnimator.update("particles", particleLayer.audioBinding, null, dt)
            val fogResult = debugAnimator.update("fog", fogLayer.audioBinding, null, dt)
            overlay.draw(
                line1 = "PARTICLES  t=%6.2fs  intensity=%.3f".format(particlesResult.elapsedSeconds, particlesResult.intensity),
                line2 = "FOG        t=%6.2fs  intensity=%.3f".format(fogResult.elapsedSeconds, fogResult.intensity)
            )

            eglSurface.setPresentationTimeNanos(frameIndex * frameDurationNanos)
            eglSurface.swapBuffers()

            drainVideo(endOfStream = false)
            frameIndex++
        }

        encoder.signalEndOfInputStream()
        drainVideo(endOfStream = true)

        overlay.release()
        compositor.release()
        eglSurface.release()
        encoder.stop()
        encoder.release()
        inputSurface.release()

        assertTrue("video encoder never started the muxer", muxerStarted)
        muxer.stop()
        muxer.release()

        assertTrue("output file missing", outputFile.exists())
        assertTrue("output file suspiciously small: ${outputFile.length()} bytes", outputFile.length() > 5_000)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(outputFile.absolutePath)
            assertEquals("expected a single video track", 1, extractor.trackCount)
            val mime = extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME) ?: ""
            assertTrue("expected a video track, got $mime", mime.startsWith("video/"))
        } finally {
            extractor.release()
        }
    }

    private fun writeTestImage(context: Context): Uri {
        val file = File(context.cacheDir, "ambient_debug_background.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(12, 10, 24))
        }.let { bitmap ->
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        return Uri.fromFile(file)
    }

    /** Draws two lines of white-on-black text as a strip across the top of the current GL surface. */
    private class TextOverlayRenderer(context: Context, private val videoWidth: Int, private val overlayHeight: Int) {
        private val quad = QuadMesh()
        private val program = ShaderProgram(
            ShaderLoader.loadAsset(context, "shaders/passthrough.vert"),
            ShaderLoader.loadAsset(context, "shaders/passthrough.frag")
        )
        private val textureId: Int
        private val bitmap = Bitmap.createBitmap(videoWidth, overlayHeight, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)
        private val backgroundPaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 15f
            isAntiAlias = true
        }
        private val mvp = FloatArray(16)

        init {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            Matrix.setIdentityM(mvp, 0)
            Matrix.translateM(mvp, 0, 0f, 0.72f, 0f)
            Matrix.scaleM(mvp, 0, 1f, 0.28f, 1f)
        }

        fun draw(line1: String, line2: String) {
            canvas.drawRect(0f, 0f, videoWidth.toFloat(), overlayHeight.toFloat(), backgroundPaint)
            canvas.drawText(line1, 8f, 40f, textPaint)
            canvas.drawText(line2, 8f, 75f, textPaint)

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

            program.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glUniform1i(program.uniformLocation("uTexture"), 0)
            GLES30.glUniform1f(program.uniformLocation("uOpacity"), 1f)
            GLES30.glUniformMatrix4fv(program.uniformLocation("uMVP"), 1, false, mvp, 0)

            quad.draw(program.attributeLocation("aPosition"), program.attributeLocation("aTexCoord"))
        }

        fun release() {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            program.release()
            bitmap.recycle()
        }
    }
}
