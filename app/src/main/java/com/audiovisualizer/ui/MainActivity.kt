package com.audiovisualizer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.audiovisualizer.app.R
import com.audiovisualizer.app.databinding.ActivityMainBinding
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import java.util.UUID

/**
 * Entry screen: pick an audio track, add image layers, toggle them on/off,
 * and (eventually) export the composited scene to MP4. Audio analysis and
 * GL rendering are wired to real layer data here; running the analyzer and
 * turning its output into live layer parameters is done in a follow-up pass.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val layers = mutableListOf<Layer>()
    private lateinit var layerAdapter: LayerListAdapter

    private var selectedAudioUri: Uri? = null

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        selectedAudioUri = uri
        Toast.makeText(this, uri.lastPathSegment, Toast.LENGTH_SHORT).show()
        // TODO: run AudioAnalyzer.analyze(uri) on a coroutine scope and store
        // the AudioAnalysisResult for layers with an AudioBinding to consume.
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
            Toast.makeText(this, getString(R.string.export_not_implemented), Toast.LENGTH_SHORT).show()
            // TODO: wire up com.audiovisualizer.export.VideoExporter (MediaCodec + MediaMuxer).
        }
    }

    private fun addLayer(layer: Layer) {
        layers.add(layer)
        layerAdapter.notifyItemInserted(layers.size - 1)
        binding.visualizerSurface.renderer.layers = layers.toList()
    }

    override fun onResume() {
        super.onResume()
        binding.visualizerSurface.onResume()
    }

    override fun onPause() {
        binding.visualizerSurface.onPause()
        super.onPause()
    }
}
