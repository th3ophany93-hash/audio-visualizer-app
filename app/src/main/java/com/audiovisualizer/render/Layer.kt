package com.audiovisualizer.render

import com.audiovisualizer.render.effects.EffectParams

data class Layer(
    val id: String,
    var name: String,
    var enabled: Boolean = true,
    var blendMode: BlendMode = BlendMode.NORMAL,
    var source: LayerSource,
    /** Used by particles/fog/glow/chromatic-aberration sources; ignored by image/video. */
    var effectParams: EffectParams = EffectParams(),
    var audioBinding: AudioBinding? = null
)
