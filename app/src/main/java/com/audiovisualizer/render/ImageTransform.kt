package com.audiovisualizer.render

/** How an image/video layer's source content maps onto the (possibly different-aspect-ratio) viewport. */
enum class FitMode {
    /** Cover the whole viewport, cropping whichever axis overflows. */
    FILL,

    /** Show the whole image, letterboxed on whichever axis is shorter. */
    FIT,

    /** Stretch to exactly fill the viewport, ignoring the image's own aspect ratio. */
    STRETCH
}

/**
 * Manual placement for an image/video layer, on top of whatever [FitMode]
 * computes as the base scale. [positionX]/[positionY] are NDC-ish offsets
 * (roughly -1..1 covers the visible area, same units a drag gesture would
 * naturally produce), [rotationRadians] is applied around the layer's own
 * center, and [scale] is an additional pinch-zoom multiplier on top of the
 * fit-mode base scale.
 */
data class ImageTransform(
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val rotationRadians: Float = 0f,
    val scale: Float = 1f,
    val fitMode: FitMode = FitMode.FILL
)

/** Pure geometry for [FitMode] - a standalone object so it's unit-testable without a GL context. */
object ImageFit {
    /**
     * The base (pre pinch-zoom, pre manual position/rotation) NDC scale that
     * makes a full -1..1 quad's on-screen pixel size honor [fitMode]
     * relative to the image's own pixel size - FILL covers the viewport
     * (cropping overflow), FIT contains it (letterboxed), STRETCH is a
     * no-op (always exactly 1,1: the quad already spans the full viewport).
     */
    fun scale(imageWidth: Int, imageHeight: Int, viewportWidth: Int, viewportHeight: Int, fitMode: FitMode): Pair<Float, Float> {
        if (fitMode == FitMode.STRETCH || imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            return 1f to 1f
        }
        val scale = when (fitMode) {
            FitMode.FIT -> minOf(viewportWidth.toFloat() / imageWidth, viewportHeight.toFloat() / imageHeight)
            FitMode.FILL -> maxOf(viewportWidth.toFloat() / imageWidth, viewportHeight.toFloat() / imageHeight)
            FitMode.STRETCH -> 1f
        }
        return (imageWidth * scale / viewportWidth) to (imageHeight * scale / viewportHeight)
    }
}
