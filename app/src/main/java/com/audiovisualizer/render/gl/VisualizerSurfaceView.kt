package com.audiovisualizer.render.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/** GLSurfaceView configured for OpenGL ES 3.0, driven by a [VisualizerRenderer]. */
class VisualizerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer = VisualizerRenderer(context.applicationContext)

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
