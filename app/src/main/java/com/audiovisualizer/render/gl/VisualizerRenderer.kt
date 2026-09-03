package com.audiovisualizer.render.gl

import android.content.Context
import android.opengl.GLSurfaceView
import com.audiovisualizer.audio.AudioPlaybackSync
import com.audiovisualizer.render.Layer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView.Renderer adapter around [LayerCompositor]: tracks the live
 * layer stack and the audio frame synced to playback, and drives one
 * [LayerCompositor.drawFrame] call per surface-view frame. The actual
 * drawing lives in [LayerCompositor] so preview and video export share it.
 */
class VisualizerRenderer(private val context: Context) : GLSurfaceView.Renderer {

    @Volatile
    var layers: List<Layer> = emptyList()

    /** Latest audio analysis, synced to playback position; null until audio is loaded. */
    @Volatile
    var audioSync: AudioPlaybackSync? = null

    private val compositor = LayerCompositor(context)
    private var lastFrameTimeNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        compositor.init()
        lastFrameTimeNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        compositor.viewportChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameTimeNanos == 0L) 0f
        else ((now - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameTimeNanos = now

        compositor.drawFrame(layers, audioSync?.currentFrame, deltaSeconds)
    }
}
