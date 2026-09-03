package com.audiovisualizer.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.audiovisualizer.app.R
import com.audiovisualizer.app.databinding.ActivityMainBinding
import com.audiovisualizer.audio.AudioAnalysisResult
import com.audiovisualizer.audio.AudioAnalyzer
import com.audiovisualizer.audio.AudioPlaybackDriver
import com.audiovisualizer.audio.AudioPlaybackSync
import com.audiovisualizer.export.MediaCodecVideoExporter
import com.audiovisualizer.export.VideoExporter
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.AudioBinding
import com.audiovisualizer.render.AudioTarget
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.render.effects.Effect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Entry screen and the app's minimal end-to-end demo: pick an audio track
 * and an image, and the picture plays back with a particle layer pulsing on
 * top of it in time with the bass - proving the full chain (audio -> FFT
 * analysis -> shader/particle parameters -> screen) actually works.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val layers = mutableListOf<Layer>()
    private lateinit var layerAdapter: LayerListAdapter

    private var selectedAudioUri: Uri? = null
    private var analysisResult: AudioAnalysisResult? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackDriver: AudioPlaybackDriver? = null
    private var exporter: MediaCodecVideoExporter? = null

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        selectedAudioUri = uri
        loadAudio(uri)
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addLayer(
            Layer(
                id = UUID.randomUUID().toString(),
                name = uri.lastPathSegment ?: "Image",
                source = LayerSource.Image(uri)
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        layerAdapter = LayerListAdapter(layers) { layer, isEnabled ->
            layer.enabled = isEnabled
        }
        binding.layersList.layoutManager = LinearLayoutManager(this)
        binding.layersList.adapter = layerAdapter

        binding.pickAudioButton.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }
        binding.pickImageButton.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }
        binding.exportButton.setOnClickListener {
            exportVideo()
        }
    }

    private fun addLayer(layer: Layer) {
        layers.add(layer)
        layerAdapter.notifyItemInserted(layers.size - 1)
        binding.visualizerSurface.renderer.layers = layers.toList()
    }

    /** Analyzes the picked track, makes sure the demo particle layer exists, then starts playback. */
    private fun loadAudio(uri: Uri) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, getString(R.string.analyzing_audio), Toast.LENGTH_SHORT).show()
            try {
                val result = AudioAnalyzer(this@MainActivity).analyze(uri)
                analysisResult = result
                ensureBassParticleLayer()
                startPlayback(uri, result)
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                Toast.makeText(this@MainActivity, getString(R.string.analysis_failed, message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Adds the demo layer once: particles on top of everything else, driven by the bass band. */
    private fun ensureBassParticleLayer() {
        if (layers.any { it.source is LayerSource.Particles }) return
        addLayer(
            Layer(
                id = "bass-particles",
                name = "Particles (bass)",
                blendMode = BlendMode.ADD,
                source = LayerSource.Particles(
                    Effect.Particles(count = 150, size = 10f, speed = 1.5f)
                ),
                audioBinding = AudioBinding(
                    band = AudioBand.BASS,
                    target = AudioTarget.PARTICLE_SPAWN_RATE,
                    sensitivity = 1.5f,
                    smoothing = 0.3f
                )
            )
        )
    }

    private suspend fun startPlayback(uri: Uri, result: AudioAnalysisResult) {
        releasePlayback()

        val player = MediaPlayer()
        withContext(Dispatchers.IO) {
            player.setDataSource(this@MainActivity, uri)
            player.prepare()
        }
        player.isLooping = true
        player.start()
        mediaPlayer = player

        val sync = AudioPlaybackSync(result)
        val driver = AudioPlaybackDriver(player, sync)
        driver.start(lifecycleScope)
        playbackDriver = driver

        binding.visualizerSurface.renderer.audioSync = sync
    }

    /** Renders the current layer stack + analyzed audio to an MP4 in the app's external files dir. */
    private fun exportVideo() {
        val audioUri = selectedAudioUri
        val analysis = analysisResult
        if (audioUri == null || analysis == null) {
            Toast.makeText(this, getString(R.string.export_needs_audio), Toast.LENGTH_SHORT).show()
            return
        }

        val outputFile = File(getExternalFilesDir(null), "export_${System.currentTimeMillis()}.mp4")
        val config = VideoExporter.ExportConfig(
            outputUri = Uri.fromFile(outputFile),
            layers = layers.toList(),
            audioUri = audioUri,
            audioAnalysis = analysis
        )

        Toast.makeText(this, getString(R.string.exporting), Toast.LENGTH_SHORT).show()
        val activeExporter = MediaCodecVideoExporter(this)
        exporter = activeExporter

        lifecycleScope.launch(Dispatchers.Default) {
            activeExporter.export(config, object : VideoExporter.ExportListener {
                override fun onProgress(fractionDone: Float) {
                    // TODO: surface progress in the UI once export has a dedicated screen.
                }

                override fun onComplete(outputUri: Uri) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, getString(R.string.export_complete, outputFile.absolutePath), Toast.LENGTH_LONG).show()
                    }
                }

                override fun onError(error: Throwable) {
                    runOnUiThread {
                        val message = error.message ?: error.toString()
                        Toast.makeText(this@MainActivity, getString(R.string.export_failed, message), Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    private fun releasePlayback() {
        playbackDriver?.stop()
        playbackDriver = null
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onResume() {
        super.onResume()
        binding.visualizerSurface.onResume()
    }

    override fun onPause() {
        binding.visualizerSurface.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        releasePlayback()
        exporter?.cancel()
        super.onDestroy()
    }
}
