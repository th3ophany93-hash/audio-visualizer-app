package com.audiovisualizer.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiovisualizer.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** Visual proof: a real screenshot of the loaded trim screen (waveform + handles + selection). */
@RunWith(AndroidJUnit4::class)
class AudioTrimScreenshotTest {

    @Test
    fun screenshotOfLoadedTrimScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val audioUri = writeTestWav(appContext)

        val intent = Intent(appContext, AudioTrimActivity::class.java).apply {
            putExtra(AudioTrimActivity.EXTRA_AUDIO_URI, audioUri.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val scenario = ActivityScenario.launch<AudioTrimActivity>(intent)
        try {
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

            scenario.onActivity { activity ->
                val waveform = activity.findViewById<WaveformView>(R.id.waveformView)
                waveform.startFraction = 0.2f
                waveform.endFraction = 0.75f
            }
            Thread.sleep(200)

            lateinit var window: android.view.Window
            var width = 0
            var height = 0
            scenario.onActivity { activity ->
                window = activity.window
                width = window.decorView.width
                height = window.decorView.height
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)
            var copyResult = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(window, bitmap, { result ->
                copyResult = result
                latch.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue("PixelCopy timed out", latch.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, copyResult)

            val outFile = File(appContext.getExternalFilesDir(null), "trim_screen_screenshot.png")
            FileOutputStream(outFile).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } finally {
            scenario.close()
        }
    }

    private fun writeTestWav(context: android.content.Context): Uri {
        val sampleRate = 44100
        val seconds = 8
        val totalSamples = sampleRate * seconds
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            // Vary amplitude over time so the waveform actually looks interesting.
            val envelope = 0.3 + 0.7 * Math.abs(sin(2.0 * Math.PI * 0.3 * t))
            pcm[i] = (sin(2.0 * Math.PI * 220.0 * t) * envelope * Short.MAX_VALUE).toInt().toShort()
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

        val file = File(context.cacheDir, "trim_screenshot_test_tone.wav")
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) body.putShort(sample)
            out.write(body.array())
        }
        return Uri.fromFile(file)
    }
}
