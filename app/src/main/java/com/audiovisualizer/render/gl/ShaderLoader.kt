package com.audiovisualizer.render.gl

import android.content.Context

/** Reads a GLSL source file bundled under assets/shaders/. */
object ShaderLoader {
    fun loadAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }
}
