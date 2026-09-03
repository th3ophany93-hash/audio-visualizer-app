package com.audiovisualizer.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes a compressed audio file into mono PCM samples normalized to [-1, 1]. */
class PcmDecoder(private val context: Context) {

    data class PcmAudio(
        val samples: FloatArray,
        val sampleRate: Int,
        val durationMs: Long
    )

    fun decode(uri: Uri): PcmAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor)
            require(trackIndex >= 0) { "No audio track found in $uri" }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Track has no MIME type")
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val monoSamples = GrowableFloatArray()
            var sawInputEos = false
            var sawOutputEos = false
            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                while (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        appendDownmixed(outputBuffer, channelCount, monoSamples)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (sawOutputEos) break
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                }
            }

            codec.stop()
            codec.release()

            val samples = monoSamples.toFloatArray()
            val durationMs = if (durationUs > 0) durationUs / 1000
            else samples.size.toLong() * 1000 / sampleRate

            return PcmAudio(samples, sampleRate, durationMs)
        } finally {
            extractor.release()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** Reads interleaved 16-bit PCM from [buffer], downmixes to mono in [-1, 1] and appends to [out]. */
    private fun appendDownmixed(buffer: ByteBuffer, channelCount: Int, out: GrowableFloatArray) {
        val shortBuffer = buffer.asShortBuffer()
        val frameCount = shortBuffer.remaining() / channelCount
        for (frame in 0 until frameCount) {
            var sum = 0
            val base = frame * channelCount
            for (ch in 0 until channelCount) {
                sum += shortBuffer.get(base + ch)
            }
            val average = sum.toFloat() / channelCount
            out.append(average / 32768f)
        }
    }
}
