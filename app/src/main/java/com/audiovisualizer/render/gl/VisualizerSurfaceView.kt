package com.audiovisualizer.render.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.audiovisualizer.render.ImageTransform
import com.audiovisualizer.render.LayerSource
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * GLSurfaceView configured for OpenGL ES 3.0, driven by a [VisualizerRenderer].
 *
 * Also owns the manual transform gestures from UX spec section 4: a
 * one-finger drag pans, and a two-finger gesture simultaneously scales
 * (pinch) and rotates. Gestures always target the topmost enabled image
 * layer - this app's simple layer stack has at most one meaningful
 * "background" to manipulate this way, so there is no separate layer
 * selection step.
 */
class VisualizerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer = VisualizerRenderer(context.applicationContext)

    private var singlePointerId = MotionEvent.INVALID_POINTER_ID
    private var twoFingerActive = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastDistance = 0f
    private var lastAngle = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                singlePointerId = event.getPointerId(0)
                twoFingerActive = false
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    twoFingerActive = true
                    val (distance, angle) = distanceAndAngle(event)
                    lastDistance = distance
                    lastAngle = angle
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingerActive && event.pointerCount >= 2) {
                    val (distance, angle) = distanceAndAngle(event)
                    val scaleFactor = if (lastDistance > 0f) distance / lastDistance else 1f
                    val rotationDelta = angle - lastAngle
                    applyTransform { t ->
                        t.copy(
                            scale = (t.scale * scaleFactor).coerceIn(0.1f, 10f),
                            rotationRadians = t.rotationRadians + rotationDelta
                        )
                    }
                    lastDistance = distance
                    lastAngle = angle
                } else {
                    val index = event.findPointerIndex(singlePointerId)
                    if (index >= 0 && width > 0 && height > 0) {
                        val x = event.getX(index)
                        val y = event.getY(index)
                        // Screen Y grows downward, NDC Y grows upward - flip it.
                        val dxNdc = 2f * (x - lastX) / width
                        val dyNdc = -2f * (y - lastY) / height
                        applyTransform { t -> t.copy(positionX = t.positionX + dxNdc, positionY = t.positionY + dyNdc) }
                        lastX = x
                        lastY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    // Back down to one finger - resume dragging from wherever it now is, no jump.
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    singlePointerId = event.getPointerId(remainingIndex)
                    twoFingerActive = false
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                singlePointerId = MotionEvent.INVALID_POINTER_ID
                twoFingerActive = false
            }
        }
        return true
    }

    private fun distanceAndAngle(event: MotionEvent): Pair<Float, Float> {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return hypot(dx, dy) to atan2(dy, dx)
    }

    private fun applyTransform(update: (ImageTransform) -> ImageTransform) {
        val target = renderer.layers.lastOrNull { it.enabled && it.source is LayerSource.Image } ?: return
        val source = target.source as LayerSource.Image
        target.source = LayerSource.Image(source.uri, update(source.transform))
    }
}
