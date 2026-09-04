package com.audiovisualizer.ui

import android.net.Uri
import android.widget.ImageButton
import android.widget.SeekBar
import com.audiovisualizer.app.R
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * UX spec section 3: the compact timeline under the preview is enabled
 * once audio is loaded, its scrub actually seeks the live preview (both
 * the MediaPlayer and the renderer's AudioPlaybackSync, keeping WYSIWYG),
 * and - critically - none of that touches what export actually uses.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTimelineTest {

    @Test
    fun timelineEnablesOnLoadAndScrubbingSeeksPreviewWithoutAffectingExportState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val audioUri = writeTestWav(appContext)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val seekBar = activity.findViewById<SeekBar>(R.id.timelineSeekBar)
                val playPause = activity.findViewById<ImageButton>(R.id.timelinePlayPauseButton)
                assertFalse("expected the timeline to start disabled with no audio loaded", seekBar.isEnabled)
                assertFalse(playPause.isEnabled)
            }

            invokeLoadAudio(scenario, audioUri)

            // Wait for analysis + playback setup to complete (progress dialog dismissed, timeline enabled).
            var ready = false
            for (attempt in 0 until 100) {
                scenario.onActivity { activity ->
                    val seekBar = activity.findViewById<SeekBar>(R.id.timelineSeekBar)
                    if (seekBar.isEnabled && seekBar.max > 0) ready = true
                }
                if (ready) break
                Thread.sleep(150)
            }
            assertTrue("expected the timeline to become enabled once audio finished loading", ready)

            val selectedUriBefore = readPrivateUriField(scenario, "selectedAudioUri")

            var progressBeforeSwipe = -1
            scenario.onActivity { activity ->
                progressBeforeSwipe = activity.findViewById<SeekBar>(R.id.timelineSeekBar).progress
            }

            // A real touch drag, like a user would perform - this is what actually
            // marks the resulting onProgressChanged callback as fromUser=true.
            onView(withId(R.id.timelineSeekBar)).perform(ViewActions.swipeRight())

            scenario.onActivity { activity ->
                val seekBar = activity.findViewById<SeekBar>(R.id.timelineSeekBar)
                val renderer = activity.findViewById<com.audiovisualizer.render.gl.VisualizerSurfaceView>(R.id.visualizerSurface).renderer
                assertTrue(
                    "expected the swipe to move the seek bar (before=$progressBeforeSwipe, after=${seekBar.progress})",
                    seekBar.progress != progressBeforeSwipe
                )
                assertTrue("expected audioSync to remain wired after scrubbing", renderer.audioSync != null)
            }

            val selectedUriAfter = readPrivateUriField(scenario, "selectedAudioUri")
            assertTrue(
                "expected scrubbing the timeline to never change which Uri export would use " +
                    "(before=$selectedUriBefore, after=$selectedUriAfter)",
                selectedUriBefore == selectedUriAfter
            )
        } finally {
            scenario.close()
        }
    }

    private fun invokeLoadAudio(scenario: ActivityScenario<MainActivity>, uri: Uri) {
        val method = MainActivity::class.java.getDeclaredMethod("loadAudio", Uri::class.java)
        method.isAccessible = true
        scenario.onActivity { activity -> method.invoke(activity, uri) }
    }

    private fun readPrivateUriField(scenario: ActivityScenario<MainActivity>, name: String): Uri? {
        var value: Uri? = null
        val field = MainActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        scenario.onActivity { activity -> value = field.get(activity) as Uri? }
        return value
    }

    private fun writeTestWav(context: android.content.Context): Uri {
        val sampleRate = 44100
        val totalSamples = sampleRate * 3
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            pcm[i] = (sin(2.0 * Math.PI * 220.0 * t) * 0.7 * Short.MAX_VALUE).toInt().toShort()
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

        val file = File(context.cacheDir, "timeline_test_tone.wav")
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) body.putShort(sample)
            out.write(body.array())
        }
        return Uri.fromFile(file)
    }
}
