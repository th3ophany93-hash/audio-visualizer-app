package com.audiovisualizer.render.gl

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiovisualizer.app.R
import com.audiovisualizer.audio.AudioAnalysisResult
import com.audiovisualizer.audio.AudioFrame
import com.audiovisualizer.audio.AudioPlaybackSync
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.AudioBinding
import com.audiovisualizer.render.AudioTarget
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.render.effects.Effect
import com.audiovisualizer.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the real GL renderer end-to-end, bypassing the SAF file pickers:
 * one static image layer plus one particle layer bound to a synthetic
 * "loud bass" audio frame, rendered on-device and captured with PixelCopy,
 * to prove the pipeline (layers -> AudioBinding -> shaders) actually
 * paints a non-blank frame rather than just compiling.
 */
@RunWith(AndroidJUnit4::class)
class VisualizerRenderScreenshotTest {

    @Test
    fun rendersImageAndBassBoundParticles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext

        // A small solid-color bitmap in the app's own cache dir, loaded via
        // a plain file:// Uri so the test needs no SAF permission grant.
        val imageFile = File(appContext.cacheDir, "test_background.png")
        val backgroundColor = Color.rgb(20, 40, 120)
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(backgroundColor)
        }.let { bitmap ->
            FileOutputStream(imageFile).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        val imageUri = Uri.fromFile(imageFile)

        // ActivityScenario.launch(Class) resolves the activity's Intent
        // against the wrong (test) package on some androidx.test versions;
        // building the Intent explicitly against the target app's own
        // package avoids that "Unable to resolve activity" failure.
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(appContext.packageName, MainActivity::class.java.name)
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val scenario = ActivityScenario.launch<MainActivity>(launchIntent)
        try {
            lateinit var view: VisualizerSurfaceView

            scenario.onActivity { activity ->
                view = activity.findViewById(R.id.visualizerSurface)
                val renderer = view.renderer

                val imageLayer = Layer(
                    id = "test-image",
                    name = "Background",
                    source = LayerSource.Image(imageUri)
                )
                val particleLayer = Layer(
                    id = "test-particles",
                    name = "Particles (bass)",
                    blendMode = BlendMode.ADD,
                    source = LayerSource.Particles(
                        Effect.Particles(count = 300, size = 24f, color = 0xFFFFFFFF.toInt())
                    ),
                    audioBinding = AudioBinding(
                        band = AudioBand.BASS,
                        target = AudioTarget.PARTICLE_SPAWN_RATE,
                        sensitivity = 2f,
                        smoothing = 0f
                    )
                )
                renderer.layers = listOf(imageLayer, particleLayer)

                val loudBassFrame = AudioFrame(timeMs = 0, bass = 1f, mid = 0f, treble = 0f, isOnset = true, onsetStrength = 1f)
                val analysis = AudioAnalysisResult(listOf(loudBassFrame), durationMs = 1000, sampleRate = 44100, frameStepMs = 1000f)
                val sync = AudioPlaybackSync(analysis)
                sync.seekTo(0)
                renderer.audioSync = sync
            }

            // The GL thread renders continuously; give it time to draw several
            // frames so particles have actually spawned and moved.
            Thread.sleep(1500)

            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)
            var copyResult = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(view, bitmap, { result ->
                copyResult = result
                latch.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue("PixelCopy timed out", latch.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, copyResult)

            // Persist it so it can be pulled off the device (adb pull) and inspected.
            val outFile = File(appContext.getExternalFilesDir(null), "render_test_screenshot.png")
            FileOutputStream(outFile).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }

            var nonBlackPixels = 0
            var backgroundLikePixels = 0
            var brightParticlePixels = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    if (r > 4 || g > 4 || b > 4) nonBlackPixels++
                    if (b > r && b > g && b in 60..200) backgroundLikePixels++
                    if (r > 200 && g > 200 && b > 200) brightParticlePixels++
                }
            }
            val totalPixels = bitmap.width * bitmap.height

            assertTrue(
                "expected a visible, non-black frame: $nonBlackPixels/$totalPixels pixels were non-black",
                nonBlackPixels > totalPixels / 10
            )
            assertTrue("expected the background image layer to be visible", backgroundLikePixels > 0)
            assertTrue("expected bright particles to be visible on top", brightParticlePixels > 0)
        } finally {
            scenario.close()
        }
    }
}
