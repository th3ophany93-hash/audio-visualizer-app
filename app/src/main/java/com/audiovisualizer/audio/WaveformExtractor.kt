package com.audiovisualizer.audio

import kotlin.math.abs

/** Downsamples decoded PCM into a fixed number of 0..1 peak-amplitude buckets, for waveform rendering. */
object WaveformExtractor {

    fun peaks(samples: FloatArray, bucketCount: Int): FloatArray {
        if (bucketCount <= 0 || samples.isEmpty()) return FloatArray(0)
        val result = FloatArray(bucketCount)
        val samplesPerBucket = samples.size.toFloat() / bucketCount
        for (bucket in 0 until bucketCount) {
            val start = (bucket * samplesPerBucket).toInt().coerceIn(0, samples.size - 1)
            val end = (((bucket + 1) * samplesPerBucket).toInt()).coerceIn(start + 1, samples.size)
            var peak = 0f
            for (i in start until end) {
                val amplitude = abs(samples[i])
                if (amplitude > peak) peak = amplitude
            }
            result[bucket] = peak.coerceIn(0f, 1f)
        }
        return result
    }
}
