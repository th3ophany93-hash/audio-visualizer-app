package com.audiovisualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A trimmer waveform, per UX spec section 2: peaks span the full width,
 * two draggable handles mark the selected [startFraction, endFraction]
 * range (highlighted), and an optional [playheadFraction] shows live
 * playback position. Dragging a handle reports through [onStartHandleDragged]/
 * [onEndHandleDragged] so the host can seek+play from that point - the
 * standard trimmer interaction.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var peaks: FloatArray = FloatArray(0)
        set(value) {
            field = value
            invalidate()
        }

    var startFraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, endFraction)
            invalidate()
        }

    var endFraction: Float = 1f
        set(value) {
            field = value.coerceIn(startFraction, 1f)
            invalidate()
        }

    /** Null hides the playhead line entirely (e.g. while paused with no meaningful position). */
    var playheadFraction: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    var onStartHandleDragged: ((Float) -> Unit)? = null
    var onEndHandleDragged: ((Float) -> Unit)? = null

    private val unselectedWavePaint = Paint().apply { color = Color.rgb(90, 90, 100); strokeWidth = 3f }
    private val selectedWavePaint = Paint().apply { color = Color.WHITE; strokeWidth = 3f }
    private val selectionPaint = Paint().apply { color = Color.argb(70, 90, 170, 255) }
    private val handlePaint = Paint().apply { color = Color.CYAN; strokeWidth = 8f }
    private val playheadPaint = Paint().apply { color = Color.RED; strokeWidth = 3f }

    private enum class Handle { START, END }
    private var draggingHandle: Handle? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val midY = h / 2f

        val selLeft = startFraction * w
        val selRight = endFraction * w
        canvas.drawRect(selLeft, 0f, selRight, h, selectionPaint)

        val currentPeaks = peaks
        if (currentPeaks.isNotEmpty()) {
            val barWidth = w / currentPeaks.size
            for (i in currentPeaks.indices) {
                val x = i * barWidth + barWidth / 2f
                val amplitude = currentPeaks[i] * midY * 0.9f
                val fraction = i.toFloat() / currentPeaks.size
                val paint = if (fraction in startFraction..endFraction) selectedWavePaint else unselectedWavePaint
                canvas.drawLine(x, midY - amplitude, x, midY + amplitude, paint)
            }
        }

        canvas.drawLine(selLeft, 0f, selLeft, h, handlePaint)
        canvas.drawLine(selRight, 0f, selRight, h, handlePaint)

        playheadFraction?.let { fraction ->
            val x = fraction * w
            canvas.drawLine(x, 0f, x, h, playheadPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        if (w <= 0f) return true
        val touchFraction = (event.x / w).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHandle = if (abs(touchFraction - startFraction) <= abs(touchFraction - endFraction)) {
                    Handle.START
                } else {
                    Handle.END
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (draggingHandle) {
                    Handle.START -> {
                        startFraction = touchFraction.coerceAtMost(endFraction)
                        onStartHandleDragged?.invoke(startFraction)
                    }
                    Handle.END -> {
                        endFraction = touchFraction.coerceAtLeast(startFraction)
                        onEndHandleDragged?.invoke(endFraction)
                    }
                    null -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingHandle = null
            }
        }
        return true
    }
}
