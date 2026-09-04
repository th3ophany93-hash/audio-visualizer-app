package com.audiovisualizer.audio

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Slices already-decoded PCM to [startMs, endMs) and writes it out as a mono 16-bit WAV file. */
object AudioTrimmer {

    fun trimToWav(pcm: PcmDecoder.PcmAudio, startMs: Long, endMs: Long, outputFile: File): Uri {
        val startSample = (startMs * pcm.sampleRate / 1000L).toInt().coerceIn(0, pcm.samples.size)
        val endSample = (endMs * pcm.sampleRate / 1000L).toInt().coerceIn(startSample, pcm.samples.size)
        val sliceLength = endSample - startSample
        val dataSize = sliceLength * 2

        FileOutputStream(outputFile).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + dataSize)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(pcm.sampleRate)
                putInt(pcm.sampleRate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(dataSize)
            }
            out.write(header.array())

            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (i in startSample until endSample) {
                val value = (pcm.samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                body.putShort(value)
            }
            out.write(body.array())
        }

        return Uri.fromFile(outputFile)
    }
}
