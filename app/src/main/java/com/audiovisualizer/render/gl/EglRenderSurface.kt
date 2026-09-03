package com.audiovisualizer.render.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * A GL ES 3.0 context and window surface bound to an arbitrary [Surface] -
 * in particular, a [android.media.MediaCodec] video encoder's input
 * surface, so [LayerCompositor] can render straight into the encoder
 * without an intermediate copy.
 */
class EglRenderSurface(surface: Surface) {

    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Could not initialize EGL" }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0
        ) { "Could not find a suitable EGL config" }
        val config = configs[0]!!

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create EGL context" }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, surfaceAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Could not create EGL window surface" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    fun setPresentationTimeNanos(nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
    }

    /** Submits the frame just drawn to whatever consumes this surface (e.g. a video encoder). */
    fun swapBuffers() {
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
