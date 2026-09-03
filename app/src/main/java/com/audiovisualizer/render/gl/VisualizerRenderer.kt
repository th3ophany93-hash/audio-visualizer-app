package com.audiovisualizer.render.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.audiovisualizer.audio.AudioPlaybackSync
import com.audiovisualizer.render.AudioTarget
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the layer stack each frame, back to front in list order (later
 * entries in [layers] paint over earlier ones - that list order is the
 * layer stack's z-order). For every enabled [Layer] it resolves the
 * layer's [com.audiovisualizer.render.AudioBinding] against the latest
 * frame from [audioSync], sets the layer's blend mode, and draws its
 * content - an image quad or a particle field - with the resolved value
 * fed into the shader/simulation.
 */
class VisualizerRenderer(private val context: Context) : GLSurfaceView.Renderer {

    @Volatile
    var layers: List<Layer> = emptyList()

    /** Latest audio analysis, synced to playback position; null until audio is loaded. */
    @Volatile
    var audioSync: AudioPlaybackSync? = null

    private lateinit var quad: QuadMesh
    private lateinit var imageProgram: ShaderProgram
    private lateinit var particleProgram: ShaderProgram

    private val bindingResolver = AudioBindingResolver()
    private val textureCache = HashMap<String, Int>()
    private val particleSystems = HashMap<String, ParticleSystem>()
    private val identityMvp = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val scratchMvp = FloatArray(16)

    private var lastFrameTimeNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)

        quad = QuadMesh()
        imageProgram = ShaderProgram(
            ShaderLoader.loadAsset(context, "shaders/passthrough.vert"),
            ShaderLoader.loadAsset(context, "shaders/passthrough.frag")
        )
        particleProgram = ShaderProgram(
            ShaderLoader.loadAsset(context, "shaders/particles.vert"),
            ShaderLoader.loadAsset(context, "shaders/particles.frag")
        )

        // GL resources from a previous context (e.g. before a surface recreation) are gone.
        textureCache.clear()
        particleSystems.clear()
        lastFrameTimeNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameTimeNanos == 0L) 0f
        else ((now - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameTimeNanos = now

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val currentFrame = audioSync?.currentFrame

        for (layer in layers) {
            if (!layer.enabled) continue
            applyBlendMode(layer.blendMode)
            val intensity = bindingResolver.resolve(layer.id, layer.audioBinding, currentFrame)

            when (val source = layer.source) {
                is LayerSource.Image -> drawImageLayer(layer, source, intensity)
                is LayerSource.Particles -> drawParticleLayer(layer, source, intensity, deltaSeconds)
                is LayerSource.Video, is LayerSource.Shader -> {
                    // TODO: video-to-texture decoding and standalone shader-effect
                    // layers land in a follow-up pass.
                }
            }
        }
    }

    private fun drawImageLayer(layer: Layer, source: LayerSource.Image, intensity: Float) {
        val textureId = textureCache.getOrPut(layer.id) {
            TextureLoader.loadFromUri(context, source.uri)
        }

        val binding = layer.audioBinding
        val opacity = if (binding?.target == AudioTarget.OPACITY) intensity else 1f
        val scale = if (binding?.target == AudioTarget.SCALE) 1f + intensity else 1f

        Matrix.setIdentityM(scratchMvp, 0)
        if (scale != 1f) {
            Matrix.scaleM(scratchMvp, 0, scale, scale, 1f)
        }

        imageProgram.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(imageProgram.uniformLocation("uTexture"), 0)
        GLES30.glUniform1f(imageProgram.uniformLocation("uOpacity"), opacity)
        GLES30.glUniformMatrix4fv(imageProgram.uniformLocation("uMVP"), 1, false, scratchMvp, 0)

        quad.draw(imageProgram.attributeLocation("aPosition"), imageProgram.attributeLocation("aTexCoord"))
    }

    private fun drawParticleLayer(
        layer: Layer,
        source: LayerSource.Particles,
        intensity: Float,
        deltaSeconds: Float
    ) {
        val system = particleSystems.getOrPut(layer.id) { ParticleSystem(source.params.count) }
        system.update(deltaSeconds, source.params, intensity)
        val vertexBuffer = system.toVertexBuffer()
        if (system.activeCount == 0) return

        particleProgram.use()
        GLES30.glUniformMatrix4fv(particleProgram.uniformLocation("uMVP"), 1, false, identityMvp, 0)
        GLES30.glUniform1f(particleProgram.uniformLocation("uPointSize"), source.params.size)

        val color = source.params.color
        val a = ((color ushr 24) and 0xFF) / 255f
        val r = ((color ushr 16) and 0xFF) / 255f
        val g = ((color ushr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        GLES30.glUniform4f(particleProgram.uniformLocation("uColor"), r, g, b, a)

        val positionAttr = particleProgram.attributeLocation("aPosition")
        val lifeAttr = particleProgram.attributeLocation("aLife")
        val stride = 3 * 4 // 3 floats per particle * 4 bytes

        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(positionAttr)
        GLES30.glVertexAttribPointer(positionAttr, 2, GLES30.GL_FLOAT, false, stride, vertexBuffer)

        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(lifeAttr)
        GLES30.glVertexAttribPointer(lifeAttr, 1, GLES30.GL_FLOAT, false, stride, vertexBuffer)

        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, system.activeCount)

        GLES30.glDisableVertexAttribArray(positionAttr)
        GLES30.glDisableVertexAttribArray(lifeAttr)
    }

    private fun applyBlendMode(mode: BlendMode) {
        when (mode) {
            BlendMode.NORMAL -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.ADD -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            BlendMode.MULTIPLY -> GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ZERO)
            BlendMode.SCREEN -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
        }
    }
}
