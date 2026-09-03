package com.audiovisualizer.export

import android.net.Uri
import com.audiovisualizer.audio.AudioAnalysisResult
import com.audiovisualizer.render.Layer

/**
 * Renders a layer stack, driven by audio analysis, to an MP4 file using
 * MediaCodec (H.264 video + AAC audio encoding) and MediaMuxer (muxing the
 * two tracks together).
 */
interface VideoExporter {

    data class ExportConfig(
        val outputUri: Uri,
        val layers: List<Layer>,
        val audioUri: Uri,
        val audioAnalysis: AudioAnalysisResult,
        val width: Int = 1080,
        val height: Int = 1920,
        val frameRate: Int = 30,
        val bitrate: Int = 8_000_000
    )

    interface ExportListener {
        fun onProgress(fractionDone: Float)
        fun onComplete(outputUri: Uri)
        fun onError(error: Throwable)
    }

    fun export(config: ExportConfig, listener: ExportListener)

    fun cancel()
}
