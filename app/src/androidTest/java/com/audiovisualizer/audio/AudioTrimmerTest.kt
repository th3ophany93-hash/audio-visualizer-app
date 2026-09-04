package com.audiovisualizer.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/** UX spec section 2: trimming actually cuts the audio to the selected range, not just visually. */
@RunWith(AndroidJUnit4::class)
class AudioTrimmerTest {

    @Test
    fun trimmedWavContainsOnlyTheSelectedRange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 6s tone: distinguishable phase isn't needed, just duration/content slicing.
        val sourceUri = writeTestWav(context, seconds = 6)
        val pcm = PcmDecoder(context).decode(sourceUri)
        assertTrue("expected the source track duration to be ~6000ms, got ${pcm.durationMs}ms", abs(pcm.durationMs - 6000L) < 50L)

        val startMs = 2000L
        val endMs = 4500L
        val outFile = File(context.cacheDir, "trim_test_output.wav")
        val trimmedUri = AudioTrimmer.trimToWav(pcm, startMs, endMs, outFile)

        val trimmedPcm = PcmDecoder(context).decode(trimmedUri)
        val expectedDurationMs = endMs - startMs
        assertTrue(
            "expected the trimmed file's duration (${trimmedPcm.durationMs}ms) to match the selected " +
                "range (${expectedDurationMs}ms)",
            abs(trimmedPcm.durationMs - expectedDurationMs) < 50L
        )

        // The trimmed audio's first sample should match the original at startMs, not at 0 -
        // proves the slice actually starts where requested, not just "the first N ms of the file".
        val startSampleIndex = (startMs * pcm.sampleRate / 1000L).toInt()
        val originalValueAtStart = pcm.samples[startSampleIndex]
        val trimmedFirstValue = trimmedPcm.samples[0]
        assertEquals(
            "expected the trimmed audio to start at the original sample for startMs",
            originalValueAtStart, trimmedFirstValue, 0.01f
        )
    }

    private fun writeTestWav(context: android.content.Context, seconds: Int): android.net.Uri {
        val sampleRate = 44100
        val totalSamples = sampleRate * seconds
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            pcm[i] = (sin(2.0 * Math.PI * 330.0 * t) * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }

        val dataSize = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }

        val file = File(context.cacheDir, "trim_test_source.wav")
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) body.putShort(sample)
            out.write(body.array())
        }
        return android.net.Uri.fromFile(file)
    }
}
