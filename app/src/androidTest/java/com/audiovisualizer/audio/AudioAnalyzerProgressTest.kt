package com.audiovisualizer.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** UX spec section 1: analysis reports an explicit stage + fraction rather than running silently. */
@RunWith(AndroidJUnit4::class)
class AudioAnalyzerProgressTest {

    @Test
    fun reportsDecodingThenAnalyzingThenDoneWithMonotonicProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioUri = writeTestWav(context)

        val events = mutableListOf<Pair<AnalysisStage, Float>>()
        runBlocking {
            AudioAnalyzer(context).analyze(audioUri) { stage, fraction -> events.add(stage to fraction) }
        }

        assertTrue("expected at least one progress event per stage", events.isNotEmpty())
        assertEquals("expected the first event to be the start of decoding", AnalysisStage.DECODING, events.first().first)
        assertEquals("expected the last event to be DONE at fraction 1", AnalysisStage.DONE, events.last().first)
        assertEquals(1f, events.last().second, 1e-4f)

        assertTrue("expected a DECODING stage event", events.any { it.first == AnalysisStage.DECODING })
        assertTrue("expected an ANALYZING stage event", events.any { it.first == AnalysisStage.ANALYZING })

        // Within a stage, fraction should never move backwards.
        for (stage in listOf(AnalysisStage.DECODING, AnalysisStage.ANALYZING)) {
            val fractions = events.filter { it.first == stage }.map { it.second }
            for (i in 1 until fractions.size) {
                assertTrue(
                    "expected $stage progress to be monotonically non-decreasing, got $fractions",
                    fractions[i] >= fractions[i - 1]
                )
            }
        }

        // DECODING must fully precede ANALYZING (stages don't interleave).
        val lastDecodingIndex = events.indexOfLast { it.first == AnalysisStage.DECODING }
        val firstAnalyzingIndex = events.indexOfFirst { it.first == AnalysisStage.ANALYZING }
        assertTrue(
            "expected all DECODING events to come before the first ANALYZING event",
            lastDecodingIndex < firstAnalyzingIndex
        )
    }

    private fun writeTestWav(context: android.content.Context): android.net.Uri {
        val sampleRate = 44100
        val totalSamples = sampleRate * 2
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            pcm[i] = (sin(2.0 * Math.PI * 220.0 * t) * 0.7 * Short.MAX_VALUE).toInt().toShort()
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

        val file = File(context.cacheDir, "analysis_progress_test_tone.wav")
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) body.putShort(sample)
            out.write(body.array())
        }
        return android.net.Uri.fromFile(file)
    }
}
