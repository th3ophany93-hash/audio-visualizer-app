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
import com.audiovisualizer.render.effects.SpawnZone
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
 * Exports a demo clip with all four layer types stacked: a static image
 * background, particles bound to bass, fog bound to bass (a manual zone +
 * color + scale on top), and glow with no binding at all - purely manual
 * plus its own ambient drift. The audio is deliberately built as a
 * moderate, steady 120 BPM beat for the first half and a sustained loud
 * "drop" for the second half, so the resulting video is itself the proof
 * that fog/glow/particles drift on their own during the beat-only half and
 * only pick up a boost during the sustained climax - never per-beat.
 */
@RunWith(AndroidJUnit4::class)
class AmbientLayersExportTest {

    @Test
    fun exportsFourLayersWithoutPerBeatPulsing() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        val audioUri = writeTestWav(appContext)
        val imageUri = writeTestImage(appContext)

        val analysis = runBlocking { AudioAnalyzer(appContext).analyze(audioUri) }
        assertTrue("analysis produced no frames", analysis.frames.isNotEmpty())

        val imageLayer = Layer(
            id = "background",
            name = "Background",
            source = LayerSource.Image(imageUri)
        )
        val particleLayer = Layer(
            id = "particles",
            name = "Particles",
            blendMode = BlendMode.ADD,
            source = LayerSource.Particles(
                Effect.Particles(count = 150, size = 14f, speed = 1.2f, color = 0xFFFFFFFF.toInt())
            ),
            audioBinding = AudioBinding(
                band = AudioBand.BASS,
                target = AudioTarget.PARTICLE_SPAWN_RATE,
                sensitivity = 1.5f
            )
        )
        val fogLayer = Layer(
            id = "fog",
            name = "Fog",
            blendMode = BlendMode.NORMAL,
            source = LayerSource.Fog(
                Effect.Fog(
                    density = 0.55f,
                    scale = 1.3f,
                    zone = SpawnZone.Rect(x = 0f, y = 0f, width = 1f, height = 0.55f),
                    color = Color.argb(180, 120, 70, 200)
                )
            ),
            audioBinding = AudioBinding(
                band = AudioBand.BASS,
                target = AudioTarget.EFFECT_INTENSITY,
                sensitivity = 1.5f,
                minValue = 0.25f,
                maxValue = 1f
            )
        )
        val glowLayer = Layer(
            id = "glow",
            name = "Glow",
            blendMode = BlendMode.ADD,
            source = LayerSource.Glow(
                Effect.Glow(
                    intensity = 1.1f,
                    radius = 22f,
                    scale = 1.4f,
                    zone = SpawnZone.Circle(centerX = 0.5f, centerY = 0.62f, radius = 0.38f),
                    color = Color.argb(255, 255, 170, 90)
                )
            )
            // No audioBinding at all: purely manual placement/color/scale, driven only by its own ambient drift.
        )

        val outputFile = File(appContext.getExternalFilesDir(null), "ambient_layers_demo.mp4")
        outputFile.delete()
        val config = VideoExporter.ExportConfig(
            outputUri = Uri.fromFile(outputFile),
            layers = listOf(imageLayer, fogLayer, particleLayer, glowLayer),
            audioUri = audioUri,
            audioAnalysis = analysis,
            width = 480,
            height = 854,
            frameRate = 20,
            bitrate = 4_000_000
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
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }
            assertTrue("expected a video track in the output", hasVideo)
            assertTrue("expected an audio track in the output", hasAudio)
        } finally {
            extractor.release()
        }
    }

    /** 6s mono WAV: a moderate 120 BPM bass pulse for the first 3s, a sustained loud bass "drop" for the last 3s. */
    private fun writeTestWav(context: Context): Uri {
        val sampleRate = 44100
        val totalSamples = sampleRate * 6
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val amplitude = if (t < 3.0) {
                val beatPhase = t % 0.5
                if (beatPhase < 0.1) 0.6 else 0.0
            } else {
                0.9
            }
            pcm[i] = (sin(2.0 * Math.PI * 80.0 * t) * amplitude * Short.MAX_VALUE).toInt().toShort()
        }

        val dataSize = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }

        val file = File(context.cacheDir, "ambient_layers_tone.wav")
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) body.putShort(sample)
            out.write(body.array())
        }
        return Uri.fromFile(file)
    }

    private fun writeTestImage(context: Context): Uri {
        val file = File(context.cacheDir, "ambient_layers_background.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(15, 12, 30))
        }.let { bitmap ->
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        return Uri.fromFile(file)
    }
}
