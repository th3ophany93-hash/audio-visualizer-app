package com.audiovisualizer.ui

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiovisualizer.app.R
import com.audiovisualizer.audio.PcmDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/**
 * Drives the real [AudioTrimActivity] end-to-end: waits for the waveform to
 * load, sets a trim selection (as a handle drag would), taps Apply, and
 * checks the returned result is a playable, correctly-shortened WAV -
 * UX spec section 2's whole point (the trimmer must actually cut the
 * audio, not just look like it does in the UI).
 */
@RunWith(AndroidJUnit4::class)
class AudioTrimActivityTest {

    @Test
    fun applyingASelectionReturnsATrimmedFileMatchingThatRange() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val audioUri = writeTestWav(appContext, seconds = 6)

        val intent = Intent(appContext, AudioTrimActivity::class.java).apply {
            putExtra(AudioTrimActivity.EXTRA_AUDIO_URI, audioUri.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val scenario = ActivityScenario.launchActivityForResult<AudioTrimActivity>(intent)
        try {
            // Wait for the waveform to finish loading (waveformView becomes visible).
            var ready = false
            for (attempt in 0 until 100) {
                scenario.onActivity { activity ->
                    val waveform = activity.findViewById<WaveformView>(R.id.waveformView)
                    if (waveform.visibility == android.view.View.VISIBLE) ready = true
                }
                if (ready) break
                Thread.sleep(100)
            }
            assertTrue("expected the waveform to finish loading", ready)

            // Select roughly the middle third of the track, like dragging both handles.
            scenario.onActivity { activity ->
                val waveform = activity.findViewById<WaveformView>(R.id.waveformView)
                waveform.startFraction = 1f / 3f
                waveform.endFraction = 2f / 3f
                val applyButton = activity.findViewById<android.widget.Button>(R.id.applyButton)
                applyButton.performClick()
            }

            Thread.sleep(500) // let the background trim + finish() complete

            val result = scenario.result
            assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
            val trimmedUriString = result.resultData?.getStringExtra(AudioTrimActivity.EXTRA_TRIMMED_AUDIO_URI)
            assertNotNull("expected a trimmed audio Uri in the result", trimmedUriString)

            val trimmedPcm = PcmDecoder(appContext).decode(Uri.parse(trimmedUriString))
            // 6s track, middle third selected -> roughly 2s.
            assertTrue(
                "expected the trimmed track to be about 2000ms (one third of 6s), got ${trimmedPcm.durationMs}ms",
                abs(trimmedPcm.durationMs - 2000L) < 100L
            )
        } finally {
            scenario.close()
        }
    }

    private fun writeTestWav(context: android.content.Context, seconds: Int): Uri {
        val sampleRate = 44100
        val totalSamples = sampleRate * seconds
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            pcm[i] = (sin(2.0 * Math.PI * 440.0 * t) * 0.8 * Short.MAX_VALUE).toInt().toShort()
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

        val file = File(context.cacheDir, "trim_activity_test_source.wav")
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) body.putShort(sample)
            out.write(body.array())
        }
        return Uri.fromFile(file)
    }
}
