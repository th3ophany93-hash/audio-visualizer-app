package com.audiovisualizer.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.audiovisualizer.audio.PcmDecoder
import com.audiovisualizer.render.gl.EglRenderSurface
import com.audiovisualizer.render.gl.LayerCompositor
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.min

/**
 * Concrete [VideoExporter]. Two passes:
 *
 * 1. The source audio is decoded (reusing [PcmDecoder], the same decoder
 *    [com.audiovisualizer.audio.AudioAnalyzer] uses) and encoded to AAC
 *    fully in memory - it's small and finishes almost instantly.
 * 2. The layer stack is rendered frame by frame - through the exact same
 *    [LayerCompositor] the live preview uses, so exported video always
 *    matches what was previewed - into an H.264 encoder's input surface,
 *    with each layer's [com.audiovisualizer.render.AudioBinding] resolved
 *    against the audio frame at that video frame's timestamp.
 *
 * Once the video encoder reports its output format, both tracks are added
 * to the [MediaMuxer], the buffered audio is flushed in, and video samples
 * are written as they're produced.
 */
class MediaCodecVideoExporter(private val context: Context) : VideoExporter {

    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override fun export(config: VideoExporter.ExportConfig, listener: VideoExporter.ExportListener) {
        cancelled = false
        try {
            val pcm = PcmDecoder(context).decode(config.audioUri)
            val audio = encodeAudioTrack(pcm.samples, pcm.sampleRate)

            val pfd = context.contentResolver.openFileDescriptor(config.outputUri, "rw")
                ?: error("Could not open ${config.outputUri} for writing")
            pfd.use { descriptor ->
                val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    encodeVideoAndMux(config, audio, muxer, listener)
                } finally {
                    muxer.release()
                }
            }

            if (cancelled) {
                listener.onError(CancellationException("Export cancelled"))
            } else {
                listener.onComplete(config.outputUri)
            }
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    private class EncodedAudio(
        val format: MediaFormat,
        val chunks: List<Pair<ByteBuffer, MediaCodec.BufferInfo>>
    )

    /** Encodes mono PCM samples (-1..1) to AAC, fully draining the encoder before returning. */
    private fun encodeAudioTrack(samples: FloatArray, sampleRate: Int): EncodedAudio {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val chunks = mutableListOf<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
        var outputFormat = format
        val bufferInfo = MediaCodec.BufferInfo()

        var sampleOffset = 0
        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    val remaining = samples.size - sampleOffset
                    val ptsUs = sampleOffset.toLong() * 1_000_000L / sampleRate
                    if (remaining <= 0) {
                        encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        val count = min(inputBuffer.remaining() / 2, remaining)
                        for (i in 0 until count) {
                            val sample = (samples[sampleOffset + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            inputBuffer.putShort(sample)
                        }
                        encoder.queueInputBuffer(inputIndex, 0, count * 2, ptsUs, 0)
                        sampleOffset += count
                    }
                }
            }

            var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = encoder.outputFormat
                } else {
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (bufferInfo.size > 0 && !isConfig) {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)!!
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val copy = ByteBuffer.allocate(bufferInfo.size).put(outputBuffer).apply { flip() }
                        val infoCopy = MediaCodec.BufferInfo()
                            .apply { set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags) }
                        chunks.add(copy to infoCopy)
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
                outputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            }
        }

        encoder.stop()
        encoder.release()
        return EncodedAudio(outputFormat, chunks)
    }

    private fun encodeVideoAndMux(
        config: VideoExporter.ExportConfig,
        audio: EncodedAudio,
        muxer: MediaMuxer,
        listener: VideoExporter.ExportListener
    ) {
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()

        val eglSurface = EglRenderSurface(inputSurface)
        eglSurface.makeCurrent()

        val compositor = LayerCompositor(context)
        compositor.init()
        compositor.viewportChanged(config.width, config.height)

        var muxerStarted = false
        var videoTrackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()

        fun startMuxer(format: MediaFormat) {
            val audioTrackIndex = muxer.addTrack(audio.format)
            videoTrackIndex = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true
            for ((data, info) in audio.chunks) {
                muxer.writeSampleData(audioTrackIndex, data, info)
            }
        }

        fun drainVideo(endOfStream: Boolean) {
            val deadlineNanos = System.nanoTime() + EOS_DRAIN_TIMEOUT_NANOS
            while (true) {
                val outputIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        check(System.nanoTime() < deadlineNanos) { "Timed out waiting for the video encoder to flush" }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) startMuxer(videoEncoder.outputFormat)
                    }
                    outputIndex >= 0 -> {
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (bufferInfo.size > 0 && !isConfig && muxerStarted) {
                            val outputBuffer = videoEncoder.getOutputBuffer(outputIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                        val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        videoEncoder.releaseOutputBuffer(outputIndex, false)
                        if (isEos) return
                    }
                }
            }
        }

        try {
            val totalFrames = ((config.audioAnalysis.durationMs * config.frameRate) / 1000L)
                .toInt().coerceAtLeast(1)
            val frameDurationNanos = 1_000_000_000L / config.frameRate

            var frameIndex = 0
            while (frameIndex < totalFrames && !cancelled) {
                val timeMs = frameIndex.toLong() * 1000L / config.frameRate
                val audioFrame = config.audioAnalysis.frameAt(timeMs)

                compositor.drawFrame(config.layers, audioFrame, 1f / config.frameRate)

                eglSurface.setPresentationTimeNanos(frameIndex * frameDurationNanos)
                eglSurface.swapBuffers()

                drainVideo(endOfStream = false)

                frameIndex++
                listener.onProgress(frameIndex.toFloat() / totalFrames)
            }

            videoEncoder.signalEndOfInputStream()
            drainVideo(endOfStream = true)
        } finally {
            compositor.release()
            eglSurface.release()
            videoEncoder.stop()
            videoEncoder.release()
            inputSurface.release()
        }

        check(muxerStarted) { "Video encoder never produced an output format; nothing was muxed" }
        muxer.stop()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val EOS_DRAIN_TIMEOUT_NANOS = 10_000_000_000L // 10s safety cap while flushing the video encoder
    }
}
