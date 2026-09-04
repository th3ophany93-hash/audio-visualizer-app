package com.audiovisualizer.render.gl

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiovisualizer.app.R
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * UX spec section 4: a one-finger drag pans the topmost enabled image
 * layer, a two-finger gesture pinches (scale) and rotates it. Verifies the
 * real [VisualizerSurfaceView.onTouchEvent] handling actually mutates the
 * layer's [com.audiovisualizer.render.ImageTransform] - the only coverage
 * this non-trivial multi-touch code has.
 */
@RunWith(AndroidJUnit4::class)
class VisualizerSurfaceViewGestureTest {

    @Test
    fun oneFingerDragMovesTheLayerAndTwoFingerPinchScalesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext

        val imageFile = File(appContext.cacheDir, "gesture_test_bg.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
            .let { bitmap -> FileOutputStream(imageFile).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } }
        val imageUri = Uri.fromFile(imageFile)

        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(appContext.packageName, MainActivity::class.java.name)
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val scenario = ActivityScenario.launch<MainActivity>(launchIntent)
        try {
            lateinit var view: VisualizerSurfaceView
            lateinit var imageLayer: Layer

            scenario.onActivity { activity ->
                view = activity.findViewById(R.id.visualizerSurface)
                imageLayer = Layer(id = "gesture-target", name = "Background", source = LayerSource.Image(imageUri))
                view.renderer.layers = listOf(imageLayer)
            }
            Thread.sleep(300) // let the view actually lay out (width/height > 0)

            scenario.onActivity {
                val before = (imageLayer.source as LayerSource.Image).transform
                assertTrue("expected the layer to start unmoved", before.positionX == 0f && before.positionY == 0f)

                // One-finger drag: down, then move 60px right and 40px down.
                val downTime = SystemClock.uptimeMillis()
                view.onTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 100f, 100f, 0))
                view.onTouchEvent(MotionEvent.obtain(downTime, downTime + 16, MotionEvent.ACTION_MOVE, 160f, 140f, 0))
                view.onTouchEvent(MotionEvent.obtain(downTime, downTime + 32, MotionEvent.ACTION_UP, 160f, 140f, 0))

                val afterDrag = (imageLayer.source as LayerSource.Image).transform
                assertTrue(
                    "expected the drag to move the layer right (positionX=${afterDrag.positionX})",
                    afterDrag.positionX > 0f
                )
                assertTrue(
                    "expected the drag to move the layer down, i.e. negative NDC Y (positionY=${afterDrag.positionY})",
                    afterDrag.positionY < 0f
                )
            }

            scenario.onActivity {
                val beforePinch = (imageLayer.source as LayerSource.Image).transform.scale

                // Two-finger pinch-out: both fingers start close together, then spread apart.
                val downTime = SystemClock.uptimeMillis()
                view.onTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 150f, 150f, 0))

                val pointerDownProps = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 })
                val pointerDownCoords = arrayOf(
                    MotionEvent.PointerCoords().apply { x = 150f; y = 150f },
                    MotionEvent.PointerCoords().apply { x = 160f; y = 150f }
                )
                val pointerDown = MotionEvent.obtain(
                    downTime, downTime + 8,
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    2, pointerDownProps, pointerDownCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
                )
                view.onTouchEvent(pointerDown)

                val moveCoords = arrayOf(
                    MotionEvent.PointerCoords().apply { x = 100f; y = 150f },
                    MotionEvent.PointerCoords().apply { x = 210f; y = 150f }
                )
                val move = MotionEvent.obtain(
                    downTime, downTime + 32, MotionEvent.ACTION_MOVE, 2, pointerDownProps, moveCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
                )
                view.onTouchEvent(move)

                val afterPinch = (imageLayer.source as LayerSource.Image).transform.scale
                assertTrue(
                    "expected pinching outward to increase scale (before=$beforePinch, after=$afterPinch)",
                    afterPinch > beforePinch
                )
            }
        } finally {
            scenario.close()
        }
    }
}
