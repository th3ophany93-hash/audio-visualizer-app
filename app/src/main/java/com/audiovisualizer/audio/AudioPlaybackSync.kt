package com.audiovisualizer.audio

/**
 * Holds the [AudioAnalysisResult] frame nearest to the current playback
 * position. A playback driver (e.g. [AudioPlaybackDriver]) writes
 * [currentFrame] as the track plays; the GL render thread reads it once per
 * drawn frame. [currentFrame] is `@Volatile` so that single read is safe
 * without locking - the writer and reader run on different threads and
 * [AudioFrame] is immutable, so there's nothing to tear.
 */
class AudioPlaybackSync(private val analysis: AudioAnalysisResult) {

    @Volatile
    var currentFrame: AudioFrame? = null
        private set

    fun seekTo(timeMs: Long) {
        currentFrame = analysis.frameAt(timeMs)
    }
}
