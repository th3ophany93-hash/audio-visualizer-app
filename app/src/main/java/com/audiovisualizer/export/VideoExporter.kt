package com.audiovisualizer.export

import android.net.Uri

/**
 * Renders the current layer stack, driven by audio analysis, to an MP4 file
 * using MediaCodec (video encoding) + MediaMuxer (muxing the encoded video
 * with the source audio track). Defines the API the export UI and the GL
 * renderer target; the encoding pipeline itself lands in a follow-up pass.
 */
interface VideoExporter {

    data class ExportConfig(
        val outputUri: Uri,
        val width: Int = 1080,
        val height: Int = 1920,
        val frameRate: Int = 30,
        val bitrate: Int = 8_000_000,
        val audioUri: Uri? = null
    )

    interface ExportListener {
        fun onProgress(fractionDone: Float)
        fun onComplete(outputUri: Uri)
        fun onError(error: Throwable)
    }

    fun export(config: ExportConfig, listener: ExportListener)

    fun cancel()
}
