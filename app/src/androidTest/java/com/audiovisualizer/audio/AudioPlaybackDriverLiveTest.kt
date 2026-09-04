package com.audiovisualizer.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.effects.EffectParams
import com.audiovisualizer.render.effects.ReactionMode
import com.audiovisualizer.render.effects.ReactionTuning
import com.audiovisualizer.render.gl.LayerAnimator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Exercises the exact live-preview audio path MainActivity uses - a real
 * MediaPlayer playing a real file, ticked by [AudioPlaybackDriver] into
 * [AudioPlaybackSync] - end to end, something no earlier test in this
 * project actually covered (every prior "proof" video used the offline
 * export path, which drives AudioFrame synthetically rather than through a
 * live MediaPlayer). Diagnostic for a bug report that live-preview
 * particles don't seem to react to audio at all.
 */
@RunWith(AndroidJUnit4::class)
class AudioPlaybackDriverLiveTest {

    @Test
    fun syncTracksRealPlaybackPositionWithNonZeroBass() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioUri = writeLoudBassWav(context)

        val analysis = runBlocking { AudioAnalyzer(context).analyze(audioUri) }
        assertTrue("analysis produced no frames", analysis.frames.isNotEmpty())
        val maxBassInAnalysis = analysis.frames.maxOf { it.bass }
        assertTrue("expected the analysis itself to see loud bass, max was $maxBassInAnalysis", maxBassInAnalysis > 0.3f)

        val player = MediaPlayer()
        player.setDataSource(context, audioUri)
        player.prepare()
        player.start()

        val sync = AudioPlaybackSync(analysis)
        val driver = AudioPlaybackDriver(player, sync)

        runBlocking {
            driver.start(this)
            val positions = mutableListOf<Long>()
            val bassValues = mutableListOf<Float>()
            var t = 0
            while (t < 30) { // ~3s at 100ms polling
                delay(100)
                positions.add(player.currentPosition.toLong())
                bassValues.add(sync.currentFrame?.bass ?: -1f)
                t++
            }
            driver.stop()

            assertTrue(
                "expected MediaPlayer.currentPosition to actually advance during playback, got $positions",
                positions.distinct().size > 1
            )
            assertTrue(
                "expected sync.currentFrame to actually be populated (non-null) during playback, got $bassValues",
                bassValues.all { it >= 0f }
            )
            val maxBassSeen = bassValues.max()
            assertTrue(
                "expected AudioPlaybackSync to reflect the loud bass track during real playback, max bass seen was $maxBassSeen (values=$bassValues)",
                maxBassSeen > 0.3f
            )
        }

        player.stop()
        player.release()
    }

    @Test
    fun beatPulseIntensityRisesQuicklyOncePlaybackReachesTheLoudSection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioUri = writeQuietThenLoudBassWav(context)
        val analysis = runBlocking { AudioAnalyzer(context).analyze(audioUri) }
        assertTrue("analysis produced no frames", analysis.frames.isNotEmpty())

        val player = MediaPlayer()
        player.setDataSource(context, audioUri)
        player.prepare()
        player.start()

        val sync = AudioPlaybackSync(analysis)
        val driver = AudioPlaybackDriver(player, sync)
        val animator = LayerAnimator()
        // Exactly what LayerParamsPanel now auto-applies when a user picks BEAT_PULSE.
        val params = EffectParams(
            reactionMode = ReactionMode.BEAT_PULSE,
            reactionTuning = ReactionTuning(band = AudioBand.BASS, attackSeconds = 0.05f, releaseSeconds = 0.25f)
        )

        runBlocking {
            driver.start(this)
            var lastTickNanos = System.nanoTime()
            var quietIntensity = 0f
            var loudIntensity = 0f
            var t = 0
            while (t < 60) { // ~6s at 100ms polling
                delay(100)
                val now = System.nanoTime()
                val dt = (now - lastTickNanos) / 1_000_000_000f
                lastTickNanos = now
                val result = animator.update("live-beatpulse", params, sync.currentFrame, dt)
                if (t == 5) quietIntensity = result.intensity // ~0.5s in, still in the quiet lead-in
                if (t >= 55) loudIntensity = maxOf(loudIntensity, result.intensity) // last ~0.5s, well into the loud section
                t++
            }
            driver.stop()

            assertTrue(
                "expected BEAT_PULSE intensity to stay low during the quiet lead-in, got $quietIntensity",
                quietIntensity < 0.4f
            )
            assertTrue(
                "expected BEAT_PULSE intensity to rise well above the quiet baseline once real playback " +
                    "reached the loud section (quiet=$quietIntensity, loud=$loudIntensity) - this is the exact " +
                    "mechanism behind the 'live preview doesn't react to audio' report",
                loudIntensity > quietIntensity + 0.3f
            )
        }

        player.stop()
        player.release()
    }

    /** 6s mono WAV: quiet for the first second, then a loud, sustained 80Hz tone - a real "drop". */
    private fun writeQuietThenLoudBassWav(context: Context): Uri {
        val sampleRate = 44100
        val totalSamples = sampleRate * 6
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val amplitude = if (t < 1.0) 0.05 else 0.95
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

        val file = File(context.cacheDir, "quiet_then_loud_test_tone.wav")
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) body.putShort(sample)
            out.write(body.array())
        }
        return Uri.fromFile(file)
    }

    /** 3s mono WAV, loud constant 80Hz tone the whole way through. */
    private fun writeLoudBassWav(context: Context): Uri {
        val sampleRate = 44100
        val totalSamples = sampleRate * 3
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            pcm[i] = (sin(2.0 * Math.PI * 80.0 * t) * 0.9 * Short.MAX_VALUE).toInt().toShort()
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

        val file = File(context.cacheDir, "live_playback_test_tone.wav")
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) body.putShort(sample)
            out.write(body.array())
        }
        return Uri.fromFile(file)
    }
}
