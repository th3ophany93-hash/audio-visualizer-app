package com.audiovisualizer.render

import com.audiovisualizer.render.effects.Effect

/**
 * One independent layer in the visualizer scene: its own texture source,
 * effect stack, blend mode, and optional binding to the audio analysis.
 */
data class Layer(
    val id: String,
    var name: String,
    var enabled: Boolean = true,
    var blendMode: BlendMode = BlendMode.NORMAL,
    var source: LayerSource,
    var effects: MutableList<Effect> = mutableListOf(),
    var audioBinding: AudioBinding? = null
)
