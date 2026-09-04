package com.audiovisualizer.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.audiovisualizer.app.R
import com.audiovisualizer.app.databinding.ActivityAudioTrimBinding
import com.audiovisualizer.audio.AudioTrimmer
import com.audiovisualizer.audio.PcmDecoder
import com.audiovisualizer.audio.WaveformExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UX spec section 2: a dedicated trim step between picking a track and
 * using it. Shows the whole track as a waveform with two draggable handles;
 * dragging either one seeks the preview to that point and plays from there
 * (the standard trimmer interaction); Play/Pause previews the current
 * selection, auto-pausing at the right handle. Applying re-encodes the
 * selected [startFraction, endFraction] span to a WAV file and returns its
 * Uri to the caller - MainActivity then analyzes/plays that instead of the
 * original pick.
 */
class AudioTrimActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioTrimBinding
    private var pcm: PcmDecoder.PcmAudio? = null
    private var durationMs: Long = 0L
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioTrimBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val audioUri = intent.getStringExtra(EXTRA_AUDIO_URI)?.let { Uri.parse(it) }
        if (audioUri == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        binding.cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.applyButton.setOnClickListener { applyTrim() }
        binding.playPauseButton.setOnClickListener { togglePlayback() }
        binding.waveformView.onStartHandleDragged = { fraction ->
            updateSelectionRangeText()
            seekAndPlayFrom(fraction)
        }
        binding.waveformView.onEndHandleDragged = { fraction ->
            updateSelectionRangeText()
            seekAndPlayFrom(fraction)
        }

        loadWaveform(audioUri)
    }

    private fun loadWaveform(audioUri: Uri) {
        lifecycleScope.launch {
            try {
                val decoded = withContext(Dispatchers.Default) { PcmDecoder(this@AudioTrimActivity).decode(audioUri) }
                pcm = decoded
                durationMs = decoded.durationMs

                val peaks = withContext(Dispatchers.Default) { WaveformExtractor.peaks(decoded.samples, WAVEFORM_BUCKETS) }
                binding.waveformView.peaks = peaks
                binding.waveformView.startFraction = 0f
                binding.waveformView.endFraction = 1f
                updateSelectionRangeText()

                val player = MediaPlayer()
                player.setDataSource(this@AudioTrimActivity, audioUri)
                player.prepare()
                player.setOnCompletionListener { pausePlayback() }
                mediaPlayer = player

                showWaveformUi()
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                Toast.makeText(this@AudioTrimActivity, getString(R.string.trim_load_failed, message), Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun showWaveformUi() {
        binding.loadingSpinner.visibility = View.GONE
        binding.loadingText.visibility = View.GONE
        binding.waveformView.visibility = View.VISIBLE
        binding.selectionRangeText.visibility = View.VISIBLE
        binding.playPauseButton.visibility = View.VISIBLE
        binding.applyButton.isEnabled = true
    }

    private fun seekAndPlayFrom(fraction: Float) {
        val player = mediaPlayer ?: return
        player.seekTo((fraction * durationMs).toInt())
        startPlayback()
    }

    private fun togglePlayback() {
        if (isPlaying) {
            pausePlayback()
        } else {
            val player = mediaPlayer ?: return
            player.seekTo((binding.waveformView.startFraction * durationMs).toInt())
            startPlayback()
        }
    }

    private fun startPlayback() {
        val player = mediaPlayer ?: return
        player.start()
        isPlaying = true
        binding.playPauseButton.text = getString(R.string.trim_pause)

        lifecycleScope.launch {
            while (isActive && isPlaying) {
                val position = player.currentPosition
                binding.waveformView.playheadFraction = if (durationMs > 0) position.toFloat() / durationMs else 0f
                val endMs = binding.waveformView.endFraction * durationMs
                if (position >= endMs) {
                    pausePlayback()
                    break
                }
                delay(PLAYHEAD_TICK_MS)
            }
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
        isPlaying = false
        binding.playPauseButton.text = getString(R.string.trim_play)
    }

    private fun updateSelectionRangeText() {
        val startMs = (binding.waveformView.startFraction * durationMs).toLong()
        val endMs = (binding.waveformView.endFraction * durationMs).toLong()
        binding.selectionRangeText.text = getString(R.string.trim_selection_range, formatMs(startMs), formatMs(endMs))
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    /** UX spec section 2/5: a progress bar for the duration of processing, then auto-return with the trimmed track. */
    private fun applyTrim() {
        val decoded = pcm ?: return
        pausePlayback()
        binding.applyButton.isEnabled = false
        binding.cancelButton.isEnabled = false
        binding.applyProgressBar.visibility = View.VISIBLE
        binding.applyProgressBar.isIndeterminate = true

        val startMs = (binding.waveformView.startFraction * durationMs).toLong()
        val endMs = (binding.waveformView.endFraction * durationMs).toLong()

        lifecycleScope.launch {
            try {
                val outputUri = withContext(Dispatchers.Default) {
                    val outFile = File(cacheDir, "trimmed_${System.currentTimeMillis()}.wav")
                    AudioTrimmer.trimToWav(decoded, startMs, endMs, outFile)
                }
                binding.applyProgressBar.isIndeterminate = false
                binding.applyProgressBar.progress = 100
                setResult(RESULT_OK, Intent().putExtra(EXTRA_TRIMMED_AUDIO_URI, outputUri.toString()))
                finish()
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                Toast.makeText(this@AudioTrimActivity, getString(R.string.trim_failed, message), Toast.LENGTH_LONG).show()
                binding.applyButton.isEnabled = true
                binding.cancelButton.isEnabled = true
                binding.applyProgressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_TRIMMED_AUDIO_URI = "trimmed_audio_uri"
        private const val WAVEFORM_BUCKETS = 300
        private const val PLAYHEAD_TICK_MS = 50L
    }
}
