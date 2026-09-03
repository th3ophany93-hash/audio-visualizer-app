package com.audiovisualizer.audio

/**
 * Picks onset (beat/hit) frames from a spectral-flux time series using a
 * local adaptive threshold: a frame is an onset when it is a local peak in
 * flux that exceeds the mean flux of a surrounding window scaled by
 * [sensitivity]. This adapts to loudness changes across a track instead of
 * relying on one fixed global threshold.
 */
object OnsetPicker {

    data class Onsets(val isOnset: BooleanArray, val strength: FloatArray)

    fun pick(flux: FloatArray, sensitivity: Float, windowRadius: Int = 5): Onsets {
        val n = flux.size
        val isOnset = BooleanArray(n)
        val strength = FloatArray(n)
        if (n == 0) return Onsets(isOnset, strength)

        val maxFlux = flux.max().takeIf { it > 0f } ?: 1f

        for (i in 0 until n) {
            val start = (i - windowRadius).coerceAtLeast(0)
            val end = (i + windowRadius).coerceAtMost(n - 1)
            var sum = 0f
            for (j in start..end) sum += flux[j]
            val localMean = sum / (end - start + 1)
            val threshold = localMean * sensitivity

            val isPeak = flux[i] > threshold &&
                (i == 0 || flux[i] >= flux[i - 1]) &&
                (i == n - 1 || flux[i] >= flux[i + 1])

            isOnset[i] = isPeak && flux[i] > 0f
            strength[i] = ((flux[i] - threshold) / (maxFlux - threshold + 1e-6f)).coerceIn(0f, 1f)
        }
        return Onsets(isOnset, strength)
    }
}
