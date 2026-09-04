package com.audiovisualizer.render.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.audiovisualizer.audio.AudioFrame
import com.audiovisualizer.render.AudioTarget
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.render.effects.SpawnZone

/**
 * Draws a layer stack into whichever GL surface is current on the calling
 * thread. Both the live preview ([VisualizerRenderer], on a GLSurfaceView)
 * and offline export (rendering into a MediaCodec encoder's input surface)
 * drive this same class, so exported video always matches what the preview
 * shows - there is exactly one place that turns layers + an audio frame
 * into draw calls.
 *
 * Each layer's resolved audio intensity, texture and particle simulation
 * state are cached keyed by [Layer.id] ([AudioBindingResolver]/[LayerAnimator]
 * included), so layers are independent by construction: one layer's
 * binding or effect parameters can never leak into another's draw call.
 *
 * Static image layers use [AudioBindingResolver] (a direct, immediate
 * band-to-value mapping). Particles, fog and glow are ambient effects -
 * they use [LayerAnimator] instead, which keeps them animating on their
 * own slow, irregular cycles and only ever lets audio contribute a rare,
 * slow-fading boost during genuine climaxes, never a per-beat pulse.
 */
class LayerCompositor(private val context: Context) {

    private lateinit var quad: QuadMesh
    private lateinit var imageProgram: ShaderProgram
    private lateinit var particleProgram: ShaderProgram
    private lateinit var fogProgram: ShaderProgram
    private lateinit var glowProgram: ShaderProgram

    private val bindingResolver = AudioBindingResolver()
    private val layerAnimator = LayerAnimator()
    private val textureCache = HashMap<String, Int>()
    private val particleSystems = HashMap<String, ParticleSystem>()
    private val fogDriftAnimators = HashMap<String, FogDriftAnimator>()
    private val identityMvp = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val scratchMvp = FloatArray(16)

    /** Call once after a GL context is current on this thread, before the first [drawFrame]. */
    fun init() {
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
        fogProgram = ShaderProgram(
            ShaderLoader.loadAsset(context, "shaders/passthrough.vert"),
            ShaderLoader.loadAsset(context, "shaders/fog.frag")
        )
        glowProgram = ShaderProgram(
            ShaderLoader.loadAsset(context, "shaders/passthrough.vert"),
            ShaderLoader.loadAsset(context, "shaders/glow.frag")
        )

        // GL resources from a previous context (e.g. before a surface recreation) are gone.
        textureCache.clear()
        particleSystems.clear()
        fogDriftAnimators.clear()
    }

    fun viewportChanged(width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    /** Clears the frame and draws every enabled layer, back to front in list order. */
    fun drawFrame(layers: List<Layer>, audioFrame: AudioFrame?, deltaSeconds: Float) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        for (layer in layers) {
            if (!layer.enabled) continue
            applyBlendMode(layer.blendMode)

            when (val source = layer.source) {
                is LayerSource.Image -> {
                    val intensity = bindingResolver.resolve(layer.id, layer.audioBinding, audioFrame)
                    drawImageLayer(layer, source, intensity)
                }
                is LayerSource.Particles -> {
                    val animation = layerAnimator.update(layer.id, layer.audioBinding, audioFrame, deltaSeconds)
                    drawParticleLayer(layer, source, animation.intensity, deltaSeconds)
                }
                is LayerSource.Fog -> {
                    val animation = layerAnimator.update(layer.id, layer.audioBinding, audioFrame, deltaSeconds)
                    val drift = fogDriftAnimators.getOrPut(layer.id) { FogDriftAnimator(layer.id.hashCode().toLong()) }
                        .update(deltaSeconds, animation.elapsedSeconds, audioFrame)
                    drawFogLayer(source, animation, drift)
                }
                is LayerSource.Glow -> {
                    val animation = layerAnimator.update(layer.id, layer.audioBinding, audioFrame, deltaSeconds)
                    drawGlowLayer(source, animation)
                }
                is LayerSource.Video, is LayerSource.Shader -> {
                    // TODO: video-to-texture decoding and standalone shader-effect
                    // layers land in a follow-up pass.
                }
            }
        }
    }

    /** Releases cached GL resources. Call while the GL context that created them is still current. */
    fun release() {
        if (textureCache.isNotEmpty()) {
            GLES30.glDeleteTextures(textureCache.size, textureCache.values.toIntArray(), 0)
            textureCache.clear()
        }
        particleSystems.clear()
        fogDriftAnimators.clear()
        if (::imageProgram.isInitialized) imageProgram.release()
        if (::particleProgram.isInitialized) particleProgram.release()
        if (::fogProgram.isInitialized) fogProgram.release()
        if (::glowProgram.isInitialized) glowProgram.release()
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

    private fun drawFogLayer(source: LayerSource.Fog, animation: LayerAnimator.Result, drift: FogDriftAnimator.Result) {
        val params = source.params
        fogProgram.use()
        GLES30.glUniformMatrix4fv(fogProgram.uniformLocation("uMVP"), 1, false, identityMvp, 0)
        GLES30.glUniform1f(fogProgram.uniformLocation("uDensity"), params.density)
        GLES30.glUniform1f(fogProgram.uniformLocation("uScale"), params.scale)
        GLES30.glUniform2f(fogProgram.uniformLocation("uOffset"), drift.offsetX, drift.offsetY)
        GLES30.glUniform1f(fogProgram.uniformLocation("uIntensity"), animation.intensity)
        setColorUniform(fogProgram, params.color)
        setZoneUniforms(fogProgram, params.zone)

        quad.draw(fogProgram.attributeLocation("aPosition"), fogProgram.attributeLocation("aTexCoord"))
    }

    private fun drawGlowLayer(source: LayerSource.Glow, animation: LayerAnimator.Result) {
        val params = source.params
        glowProgram.use()
        GLES30.glUniformMatrix4fv(glowProgram.uniformLocation("uMVP"), 1, false, identityMvp, 0)
        GLES30.glUniform1f(glowProgram.uniformLocation("uBrightness"), params.intensity)
        GLES30.glUniform1f(glowProgram.uniformLocation("uRadius"), params.radius)
        GLES30.glUniform1f(glowProgram.uniformLocation("uScale"), params.scale)
        GLES30.glUniform1f(glowProgram.uniformLocation("uTime"), animation.elapsedSeconds)
        GLES30.glUniform1f(glowProgram.uniformLocation("uIntensity"), animation.intensity)
        setColorUniform(glowProgram, params.color)
        setZoneUniforms(glowProgram, params.zone)

        quad.draw(glowProgram.attributeLocation("aPosition"), glowProgram.attributeLocation("aTexCoord"))
    }

    private fun setColorUniform(program: ShaderProgram, color: Int) {
        val a = ((color ushr 24) and 0xFF) / 255f
        val r = ((color ushr 16) and 0xFF) / 255f
        val g = ((color ushr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        GLES30.glUniform4f(program.uniformLocation("uColor"), r, g, b, a)
    }

    private fun setZoneUniforms(program: ShaderProgram, zone: SpawnZone) {
        val zoneType = program.uniformLocation("uZoneType")
        when (zone) {
            is SpawnZone.FullScreen -> {
                GLES30.glUniform1i(zoneType, 0)
            }
            is SpawnZone.Rect -> {
                GLES30.glUniform1i(zoneType, 1)
                GLES30.glUniform4f(program.uniformLocation("uZoneRect"), zone.x, zone.y, zone.width, zone.height)
            }
            is SpawnZone.Circle -> {
                GLES30.glUniform1i(zoneType, 2)
                GLES30.glUniform3f(program.uniformLocation("uZoneCircle"), zone.centerX, zone.centerY, zone.radius)
            }
        }
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
