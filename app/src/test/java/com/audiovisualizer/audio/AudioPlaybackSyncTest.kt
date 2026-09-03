package com.audiovisualizer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPlaybackSyncTest {

    private fun frameAt(timeMs: Long, bass: Float) =
        AudioFrame(timeMs = timeMs, bass = bass, mid = 0f, treble = 0f, isOnset = false, onsetStrength = 0f)

    @Test
    fun `seekTo updates currentFrame to the frame at that playback position`() {
        val frames = listOf(frameAt(0, 0.1f), frameAt(100, 0.5f), frameAt(200, 0.9f))
        val result = AudioAnalysisResult(frames, durationMs = 300, sampleRate = 44100, frameStepMs = 100f)
        val sync = AudioPlaybackSync(result)

        assertNull(sync.currentFrame)

        sync.seekTo(150)
        assertEquals(0.5f, sync.currentFrame!!.bass, 1e-6f)

        sync.seekTo(250)
        assertEquals(0.9f, sync.currentFrame!!.bass, 1e-6f)
    }
}
