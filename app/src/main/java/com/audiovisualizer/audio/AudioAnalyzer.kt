package com.audiovisualizer.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UX spec section 1: the coarse stage a caller can show to the user while [AudioAnalyzer.analyze] runs. */
enum class AnalysisStage { DECODING, ANALYZING, DONE }

/**
 * Decodes an audio file and produces, per analysis frame, normalized
 * bass/mid/treble energy and onset (beat/hit) detection over time - the
 * data the rendering layer binds layer animations to.
 */
class AudioAnalyzer(private val context: Context) {

    /** [onProgress] (stage, 0..1 fraction within that stage) is called from whatever thread the analysis runs on - marshal to the UI thread yourself if updating views. */
    suspend fun analyze(
        uri: Uri,
        config: AnalysisConfig = AnalysisConfig(),
        onProgress: (AnalysisStage, Float) -> Unit = { _, _ -> }
    ): AudioAnalysisResult =
        withContext(Dispatchers.Default) {
            onProgress(AnalysisStage.DECODING, 0f)
            val pcm = PcmDecoder(context).decode(uri) { fraction -> onProgress(AnalysisStage.DECODING, fraction) }
            val result = analyzePcm(pcm, config, onProgress)
            onProgress(AnalysisStage.DONE, 1f)
            result
        }

    private fun analyzePcm(
        pcm: PcmDecoder.PcmAudio,
        config: AnalysisConfig,
        onProgress: (AnalysisStage, Float) -> Unit
    ): AudioAnalysisResult {
        val fftSize = config.fftSize
        val binCount = fftSize / 2
        val window = WindowFunction.hann(fftSize)
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val magnitude = FloatArray(fftSize)
        val fluxCalculator = SpectralFluxCalculator(binCount)

        val rawTimesMs = ArrayList<Long>()
        val rawBass = ArrayList<Float>()
        val rawMid = ArrayList<Float>()
        val rawTreble = ArrayList<Float>()
        val rawFlux = ArrayList<Float>()

        var maxBass = 1e-6f
        var maxMid = 1e-6f
        var maxTreble = 1e-6f

        val samples = pcm.samples
        var offset = 0
        var framesSinceProgressReport = 0
        onProgress(AnalysisStage.ANALYZING, 0f)
        while (offset + fftSize <= samples.size) {
            // Reporting every frame would call back thousands of times for a typical track; every 32 is frequent enough for a smooth-looking progress bar.
            if (framesSinceProgressReport++ >= 32) {
                framesSinceProgressReport = 0
                onProgress(AnalysisStage.ANALYZING, (offset.toFloat() / samples.size).coerceIn(0f, 1f))
            }
            for (i in 0 until fftSize) {
                real[i] = samples[offset + i] * window[i]
                imag[i] = 0f
            }
            FFT.transform(real, imag)
            FFT.magnitude(real, imag, magnitude)

            val bands = BandEnergyCalculator.compute(
                magnitude, pcm.sampleRate, fftSize,
                config.bassRange, config.midRange, config.trebleRange
            )
            val flux = fluxCalculator.nextFlux(magnitude)

            if (bands.bass > maxBass) maxBass = bands.bass
            if (bands.mid > maxMid) maxMid = bands.mid
            if (bands.treble > maxTreble) maxTreble = bands.treble

            rawTimesMs.add(offset.toLong() * 1000L / pcm.sampleRate)
            rawBass.add(bands.bass)
            rawMid.add(bands.mid)
            rawTreble.add(bands.treble)
            rawFlux.add(flux)

            offset += config.hopSize
        }

        val onsets = OnsetPicker.pick(rawFlux.toFloatArray(), config.onsetSensitivity, config.onsetWindowRadius)

        val frames = rawTimesMs.indices.map { i ->
            AudioFrame(
                timeMs = rawTimesMs[i],
                bass = (rawBass[i] / maxBass).coerceIn(0f, 1f),
                mid = (rawMid[i] / maxMid).coerceIn(0f, 1f),
                treble = (rawTreble[i] / maxTreble).coerceIn(0f, 1f),
                isOnset = onsets.isOnset[i],
                onsetStrength = onsets.strength[i]
            )
        }

        val frameStepMs = config.hopSize.toFloat() * 1000f / pcm.sampleRate
        return AudioAnalysisResult(frames, pcm.durationMs, pcm.sampleRate, frameStepMs)
    }
}
