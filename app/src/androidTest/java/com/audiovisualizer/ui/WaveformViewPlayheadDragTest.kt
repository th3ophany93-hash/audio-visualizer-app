package com.audiovisualizer.ui

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bug fix regression: the trimmer's current playback position (playhead) must
 * itself be draggable, clamped to [startFraction, endFraction] - not just the
 * two selection handles.
 */
@RunWith(AndroidJUnit4::class)
class WaveformViewPlayheadDragTest {

    @Test
    fun draggingNearThePlayheadMovesItAndClampsToTheSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = WaveformView(context)
        view.layout(0, 0, 1000, 200)
        view.startFraction = 0.2f
        view.endFraction = 0.8f
        view.playheadFraction = 0.5f

        val reported = mutableListOf<Float>()
        view.onPlayheadDragged = { reported.add(it) }

        // Touch down right on the playhead (x=500 -> fraction 0.5), far from either handle.
        dispatch(view, MotionEvent.ACTION_DOWN, 500f)
        // Drag past the right selection boundary - should clamp to endFraction.
        dispatch(view, MotionEvent.ACTION_MOVE, 950f)
        assertEquals(0.8f, view.playheadFraction!!, 0.001f)
        assertEquals(0.8f, reported.last(), 0.001f)

        // Drag past the left selection boundary - should clamp to startFraction.
        dispatch(view, MotionEvent.ACTION_MOVE, 50f)
        assertEquals(0.2f, view.playheadFraction!!, 0.001f)
        assertEquals(0.2f, reported.last(), 0.001f)

        dispatch(view, MotionEvent.ACTION_UP, 50f)

        // The selection handles themselves must be untouched by the playhead drag.
        assertEquals(0.2f, view.startFraction, 0.001f)
        assertEquals(0.8f, view.endFraction, 0.001f)
    }

    @Test
    fun touchingNearAHandleStillDragsTheHandleNotThePlayhead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = WaveformView(context)
        view.layout(0, 0, 1000, 200)
        view.startFraction = 0.2f
        view.endFraction = 0.8f
        view.playheadFraction = 0.5f

        var endDragged: Float? = null
        view.onEndHandleDragged = { endDragged = it }
        var playheadDragged: Float? = null
        view.onPlayheadDragged = { playheadDragged = it }

        // Touch right on the end handle (x=800 -> fraction 0.8), far from the playhead (0.5).
        dispatch(view, MotionEvent.ACTION_DOWN, 800f)
        dispatch(view, MotionEvent.ACTION_MOVE, 900f)
        dispatch(view, MotionEvent.ACTION_UP, 900f)

        assertEquals(0.9f, view.endFraction, 0.001f)
        assertEquals(0.9f, endDragged!!, 0.001f)
        assertEquals(null, playheadDragged)
    }

    private fun dispatch(view: WaveformView, action: Int, x: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, 100f, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
