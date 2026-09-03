package com.audiovisualizer.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiovisualizer.audio.AudioAnalyzer
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.AudioBinding
import com.audiovisualizer.render.AudioTarget
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.render.effects.Effect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Exercises the real export pipeline end to end: a synthetic WAV (loud bass
 * for the first half, loud treble for the second) drives one image layer
 * bound to TREBLE/OPACITY and one particle layer bound to BASS/SPAWN_RATE -
 * different bands and different targets, so if a layer's binding ever leaked
 * into another layer's draw call, the two halves of the clip would look
 * wrong in the same way. The test asserts the exporter actually produces a
 * playable two-track MP4, not just that it runs without throwing.
 */
@RunWith(AndroidJUnit4::class)
class VideoExportTest {

    @Test
    fun exportsAnMp4WithIndependentImageAndParticleLayers() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        val audioUri = writeTestWav(appContext)
        val imageUri = writeTestImage(appContext)

        val analysis = runBlocking { AudioAnalyzer(appContext).analyze(audioUri) }
        assertTrue("analysis produced no frames", analysis.frames.isNotEmpty())

        val imageLayer = Layer(
            id = "bg-image",
            name = "Background",
            source = LayerSource.Image(imageUri),
            audioBinding = AudioBinding(
                band = AudioBand.TREBLE,
                target = AudioTarget.OPACITY,
                sensitivity = 1.5f,
                smoothing = 0.1f,
                minValue = 0.15f,
                maxValue = 1f
            )
        )
        val particleLayer = Layer(
            id = "fg-particles",
            name = "Particles (bass)",
            blendMode = BlendMode.ADD,
            source = LayerSource.Particles(
                Effect.Particles(count = 200, size = 16f, speed = 1.5f, color = 0xFFFFFFFF.toInt())
            ),
            audioBinding = AudioBinding(
                band = AudioBand.BASS,
                target = AudioTarget.PARTICLE_SPAWN_RATE,
                sensitivity = 2f,
                smoothing = 0.2f
            )
        )

        val outputFile = File(appContext.getExternalFilesDir(null), "export_test_output.mp4")
        outputFile.delete()
        val config = VideoExporter.ExportConfig(
            outputUri = Uri.fromFile(outputFile),
            layers = listOf(imageLayer, particleLayer),
            audioUri = audioUri,
            audioAnalysis = analysis,
            width = 480,
            height = 854,
            frameRate = 24,
            bitrate = 3_000_000
        )

        var completedUri: Uri? = null
        var exportError: Throwable? = null
        MediaCodecVideoExporter(appContext).export(config, object : VideoExporter.ExportListener {
            override fun onProgress(fractionDone: Float) {}
            override fun onComplete(outputUri: Uri) {
                completedUri = outputUri
            }

            override fun onError(error: Throwable) {
                exportError = error
            }
        })

        assertNull("export failed: $exportError", exportError)
        assertEquals(Uri.fromFile(outputFile), completedUri)
        assertTrue("output file missing", outputFile.exists())
        assertTrue("output file suspiciously small: ${outputFile.length()} bytes", outputFile.length() > 5_000)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(outputFile.absolutePath)
            assertEquals("expected exactly one video + one audio track", 2, extractor.trackCount)

            var hasVideo = false
            var hasAudio = false
            var videoDurationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                when {
                    mime.startsWith("video/") -> {
                        hasVideo = true
                        if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            videoDurationUs = format.getLong(MediaFormat.KEY_DURATION)
                        }
                    }
                    mime.startsWith("audio/") -> hasAudio = true
                }
            }
            assertTrue("expected a video track in the output", hasVideo)
            assertTrue("expected an audio track in the output", hasAudio)
            if (videoDurationUs > 0) {
                assertTrue(
                    "expected roughly a 2s clip, got ${videoDurationUs / 1000}ms",
                    videoDurationUs in 1_500_000..3_000_000
                )
            }
        } finally {
            extractor.release()
        }
    }

    /** A 2s mono WAV: loud 80Hz (bass) for the first second, loud 6000Hz (treble) for the second. */
    private fun writeTestWav(context: Context): Uri {
        val sampleRate = 44100
        val totalSamples = sampleRate * 2
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val freq = if (t < 1.0) 80.0 else 6000.0
            pcm[i] = (sin(2.0 * Math.PI * freq * t) * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }

        val dataSize = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // subchunk1 size for PCM
            putShort(1) // audio format = PCM
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // byte rate = sampleRate * channels * bytesPerSample
            putShort(2) // block align = channels * bytesPerSample
            putShort(16) // bits per sample
            put("data".toByteArray())
            putInt(dataSize)
        }

        val file = File(context.cacheDir, "export_test_tone.wav")
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) body.putShort(sample)
            out.write(body.array())
        }
        return Uri.fromFile(file)
    }

    private fun writeTestImage(context: Context): Uri {
        val file = File(context.cacheDir, "export_test_background.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 20, 90))
        }.let { bitmap ->
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        return Uri.fromFile(file)
    }
}
