package com.audiovisualizer.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes an audio file and produces, per analysis frame, normalized
 * bass/mid/treble energy and onset (beat/hit) detection over time - the
 * data the rendering layer binds layer animations to.
 */
class AudioAnalyzer(private val context: Context) {

    suspend fun analyze(uri: Uri, config: AnalysisConfig = AnalysisConfig()): AudioAnalysisResult =
        withContext(Dispatchers.Default) {
            val pcm = PcmDecoder(context).decode(uri)
            analyzePcm(pcm, config)
        }

    private fun analyzePcm(pcm: PcmDecoder.PcmAudio, config: AnalysisConfig): AudioAnalysisResult {
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
        while (offset + fftSize <= samples.size) {
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
