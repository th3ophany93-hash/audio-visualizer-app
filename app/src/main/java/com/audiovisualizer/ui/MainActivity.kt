package com.audiovisualizer.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.PopupMenu
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
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.render.effects.Effect
import com.audiovisualizer.render.effects.EffectParams
import com.audiovisualizer.render.effects.ReactionMode
import com.audiovisualizer.render.effects.ReactionTuning
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
        binding.addEffectButton.setOnClickListener { showAddEffectMenu(it) }
    }

    /** Lets the user add a fresh particles/fog/glow/chromatic-aberration layer to try out its [EffectParams] panel. */
    private fun showAddEffectMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        val items = listOf(
            getString(R.string.effect_particles) to { addEffectLayer(EffectType.PARTICLES) },
            getString(R.string.effect_fog) to { addEffectLayer(EffectType.FOG) },
            getString(R.string.effect_glow) to { addEffectLayer(EffectType.GLOW) },
            getString(R.string.effect_chromatic_aberration) to { addEffectLayer(EffectType.CHROMATIC_ABERRATION) }
        )
        items.forEachIndexed { index, (title, _) -> popup.menu.add(0, index, index, title) }
        popup.setOnMenuItemClickListener { item ->
            items[item.itemId].second.invoke()
            true
        }
        popup.show()
    }

    private enum class EffectType { PARTICLES, FOG, GLOW, CHROMATIC_ABERRATION }

    /**
     * Every new layer defaults to [EffectParams]() (AMBIENT_ONLY, no beat
     * flicker) so it starts out with exactly the same ambient-wander-only
     * look every effect layer in this app has always had by default -
     * SMOOTH_CLIMAX/BEAT_PULSE/beatFlicker are opt-in from the panel.
     */
    private fun addEffectLayer(type: EffectType) {
        val id = UUID.randomUUID().toString()
        val layer = when (type) {
            EffectType.PARTICLES -> Layer(
                id = id,
                name = getString(R.string.effect_particles),
                blendMode = BlendMode.ADD,
                source = LayerSource.Particles(Effect.Particles())
            )
            EffectType.FOG -> Layer(
                id = id,
                name = getString(R.string.effect_fog),
                source = LayerSource.Fog(Effect.Fog())
            )
            EffectType.GLOW -> Layer(
                id = id,
                name = getString(R.string.effect_glow),
                blendMode = BlendMode.ADD,
                source = LayerSource.Glow(Effect.Glow())
            )
            EffectType.CHROMATIC_ABERRATION -> Layer(
                id = id,
                name = getString(R.string.effect_chromatic_aberration),
                source = LayerSource.ChromaticAberration(Effect.ChromaticAberration())
            )
        }
        addLayer(layer)
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
                effectParams = EffectParams(
                    reactionMode = ReactionMode.SMOOTH_CLIMAX,
                    reactionTuning = ReactionTuning(sensitivity = 1.5f)
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
