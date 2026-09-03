package com.audiovisualizer.render.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the layer stack in order, back to front. Each [Layer]'s texture,
 * effect shaders and audio-driven parameters are wired up in a follow-up
 * pass - for now the frame is cleared and per-layer blend state is set up
 * so the compositing loop is ready to receive real draw calls.
 */
class VisualizerRenderer : GLSurfaceView.Renderer {

    @Volatile
    var layers: List<Layer> = emptyList()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        for (layer in layers) {
            if (!layer.enabled) continue
            applyBlendMode(layer.blendMode)
            // TODO: bind layer.source texture, run its effect shaders with
            // parameters resolved from layer.audioBinding, draw a quad.
        }
    }

    private fun applyBlendMode(mode: BlendMode) {
        when (mode) {
            BlendMode.NORMAL -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.ADD -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            BlendMode.MULTIPLY -> GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ZERO)
            BlendMode.SCREEN -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
        }
    }
}
