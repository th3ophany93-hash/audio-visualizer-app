package com.audiovisualizer.audio

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ticks [AudioPlaybackSync] to the [MediaPlayer]'s current position roughly
 * every frame, so the analysis data the renderer reads stays in sync with
 * what's actually audible.
 */
class AudioPlaybackDriver(
    private val mediaPlayer: MediaPlayer,
    private val sync: AudioPlaybackSync
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                sync.seekTo(mediaPlayer.currentPosition.toLong())
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TICK_MS = 16L
    }
}
