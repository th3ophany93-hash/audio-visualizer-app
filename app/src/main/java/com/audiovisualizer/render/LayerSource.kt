package com.audiovisualizer.render

import android.net.Uri
import com.audiovisualizer.render.effects.Effect

/** The visual content a [Layer] draws before its effects are applied. */
sealed class LayerSource {
    data class Image(val uri: Uri) : LayerSource()
    data class Video(val uri: Uri) : LayerSource()
    data class Particles(val params: Effect.Particles) : LayerSource()
    data class Shader(val shaderName: String) : LayerSource()
}
