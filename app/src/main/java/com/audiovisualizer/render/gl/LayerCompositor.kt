package com.audiovisualizer.render.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.audiovisualizer.audio.AudioFrame
import com.audiovisualizer.render.AudioTarget
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.render.effects.ColorSpec
import com.audiovisualizer.render.effects.EffectParams
import com.audiovisualizer.render.effects.SpawnZone

/**
 * Draws a layer stack into whichever GL surface is current on the calling
 * thread. Both the live preview ([VisualizerRenderer], on a GLSurfaceView)
 * and offline export (rendering into a MediaCodec encoder's input surface)
 * drive this same class, so exported video always matches what the preview
 * shows - there is exactly one place that turns layers + an audio frame
 * into draw calls.
 *
 * Every layer is drawn into a ping-pong pair of offscreen FBOs rather than
 * straight to the screen, so a [LayerSource.ChromaticAberration] layer can
 * sample everything composited below it: it distorts the FBO built up so
 * far into the other FBO, then subsequent layers keep drawing on top of
 * that. The final accumulated FBO is blitted to the real target (screen or
 * encoder surface) once at the end of [drawFrame]. For a layer stack with no
 * chromatic aberration layer this produces pixel-identical output to
 * drawing straight to the screen - see the comment on [blitToScreen].
 *
 * Each layer's resolved audio intensity, texture and particle simulation
 * state are cached keyed by [Layer.id] ([AudioBindingResolver]/[LayerAnimator]
 * included), so layers are independent by construction: one layer's
 * binding or effect parameters can never leak into another's draw call.
 *
 * Static image layers use [AudioBindingResolver] (a direct, immediate
 * band-to-value mapping). Particles, fog, glow and chromatic aberration are
 * driven by [Layer.effectParams] instead: [EffectParams.reactionMode] picks
 * how (if at all) audio drives their animated intensity, defaulting to the
 * same ambient-wander-only / rare-climax-boost behavior this app always had.
 */
class LayerCompositor(private val context: Context) {

    private lateinit var quad: QuadMesh
    private lateinit var imageProgram: ShaderProgram
    private lateinit var particleProgram: ShaderProgram
    private lateinit var fogProgram: ShaderProgram
    private lateinit var glowProgram: ShaderProgram
    private lateinit var chromaticAberrationProgram: ShaderProgram

    private val bindingResolver = AudioBindingResolver()
    private val layerAnimator = LayerAnimator()
    private val textureCache = HashMap<String, Int>()
    private val particleSystems = HashMap<String, ParticleSystem>()
    private val fogDriftAnimators = HashMap<String, FogDriftAnimator>()
    private val identityMvp = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val scratchMvp = FloatArray(16)

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var fboA: Fbo? = null
    private var fboB: Fbo? = null

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
        chromaticAberrationProgram = ShaderProgram(
            ShaderLoader.loadAsset(context, "shaders/passthrough.vert"),
            ShaderLoader.loadAsset(context, "shaders/chromatic_aberration.frag")
        )

        // GL resources from a previous context (e.g. before a surface recreation) are gone.
        textureCache.clear()
        particleSystems.clear()
        fogDriftAnimators.clear()
        fboA?.release()
        fboB?.release()
        fboA = null
        fboB = null
    }

    fun viewportChanged(width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        fboA?.release()
        fboB?.release()
        fboA = Fbo(width, height)
        fboB = Fbo(width, height)
    }

    /** Clears the frame and draws every enabled layer, back to front in list order. */
    fun drawFrame(layers: List<Layer>, audioFrame: AudioFrame?, deltaSeconds: Float) {
        val a = fboA
        val b = fboB
        if (a == null || b == null || viewportWidth == 0 || viewportHeight == 0) return

        var current: Fbo = a
        var other: Fbo = b

        current.bind()
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        for (layer in layers) {
            if (!layer.enabled) continue
            val params = layer.effectParams

            when (val source = layer.source) {
                is LayerSource.Image -> {
                    applyBlendMode(layer.blendMode)
                    val intensity = bindingResolver.resolve(layer.id, layer.audioBinding, audioFrame)
                    drawImageLayer(layer, source, intensity)
                }
                is LayerSource.Particles -> {
                    if (!params.enabled) continue
                    applyBlendMode(params.blendMode)
                    val animation = layerAnimator.update(layer.id, params, audioFrame, deltaSeconds)
                    drawParticleLayer(layer.id, source, params, animation.intensity, deltaSeconds)
                }
                is LayerSource.Fog -> {
                    if (!params.enabled) continue
                    applyBlendMode(params.blendMode)
                    val animation = layerAnimator.update(layer.id, params, audioFrame, deltaSeconds)
                    val drift = fogDriftAnimators.getOrPut(layer.id) { FogDriftAnimator(layer.id.hashCode().toLong()) }
                        .update(deltaSeconds, animation.elapsedSeconds, audioFrame, params.movement, source.params.driftSpeedX, source.params.driftSpeedY)
                    drawFogLayer(source, params, animation, drift)
                }
                is LayerSource.Glow -> {
                    if (!params.enabled) continue
                    applyBlendMode(params.blendMode)
                    val animation = layerAnimator.update(layer.id, params, audioFrame, deltaSeconds)
                    drawGlowLayer(source, params, animation)
                }
                is LayerSource.ChromaticAberration -> {
                    if (!params.enabled) continue
                    val animation = layerAnimator.update(layer.id, params, audioFrame, deltaSeconds)
                    // Distort what's accumulated in `current` into `other`,
                    // straight overwrite (no blending - this replaces the
                    // whole buffer with a transformed copy of itself), then
                    // swap so later layers keep drawing on top of the result.
                    other.bind()
                    drawChromaticAberrationLayer(source, params, animation.intensity, current.texture)
                    val swap = current
                    current = other
                    other = swap
                }
                is LayerSource.Video, is LayerSource.Shader -> {
                    // TODO: video-to-texture decoding and standalone shader-effect
                    // layers land in a follow-up pass.
                }
            }
        }

        blitToScreen(current.texture)
    }

    /** Releases cached GL resources. Call while the GL context that created them is still current. */
    fun release() {
        if (textureCache.isNotEmpty()) {
            GLES30.glDeleteTextures(textureCache.size, textureCache.values.toIntArray(), 0)
            textureCache.clear()
        }
        particleSystems.clear()
        fogDriftAnimators.clear()
        fboA?.release()
        fboB?.release()
        fboA = null
        fboB = null
        if (::imageProgram.isInitialized) imageProgram.release()
        if (::particleProgram.isInitialized) particleProgram.release()
        if (::fogProgram.isInitialized) fogProgram.release()
        if (::glowProgram.isInitialized) glowProgram.release()
        if (::chromaticAberrationProgram.isInitialized) chromaticAberrationProgram.release()
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
        layerId: String,
        source: LayerSource.Particles,
        params: EffectParams,
        intensity: Float,
        deltaSeconds: Float
    ) {
        val system = particleSystems.getOrPut(layerId) { ParticleSystem(source.params.count) }
        system.update(deltaSeconds, source.params, params.zone, intensity)
        val vertexBuffer = system.toVertexBuffer()
        if (system.activeCount == 0) return

        particleProgram.use()
        GLES30.glUniformMatrix4fv(particleProgram.uniformLocation("uMVP"), 1, false, scaledMvp(params), 0)
        GLES30.glUniform1f(particleProgram.uniformLocation("uPointSize"), source.params.size)
        GLES30.glUniform1f(particleProgram.uniformLocation("uOpacity"), params.opacity)
        setGradientUniforms(particleProgram, params.color)

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

    private fun drawFogLayer(source: LayerSource.Fog, params: EffectParams, animation: LayerAnimator.Result, drift: FogDriftAnimator.Result) {
        val fog = source.params
        fogProgram.use()
        GLES30.glUniformMatrix4fv(fogProgram.uniformLocation("uMVP"), 1, false, scaledMvp(params), 0)
        GLES30.glUniform1f(fogProgram.uniformLocation("uDensity"), fog.density)
        GLES30.glUniform1f(fogProgram.uniformLocation("uScale"), fog.noiseScale)
        GLES30.glUniform2f(fogProgram.uniformLocation("uOffset"), drift.offsetX, drift.offsetY)
        GLES30.glUniform1f(fogProgram.uniformLocation("uIntensity"), animation.intensity)
        GLES30.glUniform1f(fogProgram.uniformLocation("uOpacity"), params.opacity)
        setGradientUniforms(fogProgram, params.color)
        setZoneUniforms(fogProgram, params.zone)

        quad.draw(fogProgram.attributeLocation("aPosition"), fogProgram.attributeLocation("aTexCoord"))
    }

    private fun drawGlowLayer(source: LayerSource.Glow, params: EffectParams, animation: LayerAnimator.Result) {
        val glow = source.params
        glowProgram.use()
        GLES30.glUniformMatrix4fv(glowProgram.uniformLocation("uMVP"), 1, false, scaledMvp(params), 0)
        GLES30.glUniform1f(glowProgram.uniformLocation("uBrightness"), glow.intensity)
        GLES30.glUniform1f(glowProgram.uniformLocation("uRadius"), glow.radius)
        GLES30.glUniform1f(glowProgram.uniformLocation("uScale"), 1f)
        GLES30.glUniform1f(glowProgram.uniformLocation("uTime"), animation.elapsedSeconds)
        GLES30.glUniform1f(glowProgram.uniformLocation("uIntensity"), animation.intensity)
        GLES30.glUniform1f(glowProgram.uniformLocation("uOpacity"), params.opacity)
        val driftSpeed = if (params.movement.enabled) params.movement.speed else 0f
        GLES30.glUniform1f(glowProgram.uniformLocation("uDriftSpeed"), driftSpeed)
        GLES30.glUniform1f(glowProgram.uniformLocation("uDriftAngleOffset"), params.movement.direction)
        setGradientUniforms(glowProgram, params.color)
        setZoneUniforms(glowProgram, params.zone)

        quad.draw(glowProgram.attributeLocation("aPosition"), glowProgram.attributeLocation("aTexCoord"))
    }

    private fun drawChromaticAberrationLayer(
        source: LayerSource.ChromaticAberration,
        params: EffectParams,
        intensity: Float,
        sourceTexture: Int
    ) {
        // Full-buffer transform of everything below this layer: straight
        // overwrite, no blending against whatever stale content `other` had.
        GLES30.glDisable(GLES30.GL_BLEND)

        chromaticAberrationProgram.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
        GLES30.glUniform1i(chromaticAberrationProgram.uniformLocation("uTexture"), 0)
        GLES30.glUniformMatrix4fv(chromaticAberrationProgram.uniformLocation("uMVP"), 1, false, identityMvp, 0)
        GLES30.glUniform1f(chromaticAberrationProgram.uniformLocation("uStrength"), source.params.strength * intensity)
        GLES30.glUniform1f(chromaticAberrationProgram.uniformLocation("uOpacity"), params.opacity)
        setZoneUniforms(chromaticAberrationProgram, params.zone)

        quad.draw(
            chromaticAberrationProgram.attributeLocation("aPosition"),
            chromaticAberrationProgram.attributeLocation("aTexCoord")
        )

        GLES30.glEnable(GLES30.GL_BLEND)
    }

    /**
     * Copies the final composited FBO straight onto whatever framebuffer is
     * bound as the render target (screen or export surface), with blending
     * disabled. A blended copy would double-apply the FBO's own accumulated
     * alpha channel on top of already-correct RGB (each layer's own blend
     * already resolved color against a black background while drawing into
     * the FBO - the FBO's alpha coverage is irrelevant to that RGB and must
     * not be reapplied), so this is a raw copy: pixel-identical to every
     * layer having been drawn straight onto the screen with no FBO at all.
     */
    private fun blitToScreen(texture: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        GLES30.glDisable(GLES30.GL_BLEND)
        imageProgram.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(imageProgram.uniformLocation("uTexture"), 0)
        GLES30.glUniform1f(imageProgram.uniformLocation("uOpacity"), 1f)
        GLES30.glUniformMatrix4fv(imageProgram.uniformLocation("uMVP"), 1, false, identityMvp, 0)
        quad.draw(imageProgram.attributeLocation("aPosition"), imageProgram.attributeLocation("aTexCoord"))
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    private fun scaledMvp(params: EffectParams): FloatArray {
        Matrix.setIdentityM(scratchMvp, 0)
        if (params.scaleX != 1f || params.scaleY != 1f) {
            Matrix.scaleM(scratchMvp, 0, params.scaleX, params.scaleY, 1f)
        }
        return scratchMvp
    }

    /**
     * Sets uColor0/1/2 + uStopPos0/1/2 + uStopCount. Deliberately plain named
     * uniforms rather than a uColorStops[3]/uStopPositions[3] array indexed
     * in a shader-side loop - that pattern reproducibly crashed the
     * SwiftShader software renderer used by this project's test emulator.
     */
    private fun setGradientUniforms(program: ShaderProgram, colorSpec: ColorSpec) {
        val stops = colorSpec.stops
        for (i in 0 until 3) {
            val stop = if (i < stops.size) stops[i] else stops.last()
            val a = ((stop.color ushr 24) and 0xFF) / 255f
            val r = ((stop.color ushr 16) and 0xFF) / 255f
            val g = ((stop.color ushr 8) and 0xFF) / 255f
            val b = (stop.color and 0xFF) / 255f
            val position = if (i < stops.size) stop.position else 1f
            GLES30.glUniform4f(program.uniformLocation("uColor$i"), r, g, b, a)
            GLES30.glUniform1f(program.uniformLocation("uStopPos$i"), position)
        }
        GLES30.glUniform1i(program.uniformLocation("uStopCount"), stops.size.coerceIn(1, 3))
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

    /** A single color-texture-backed framebuffer, sized to the current viewport. */
    private class Fbo(width: Int, height: Int) {
        val texture: Int
        private val framebuffer: Int

        init {
            val textureIds = IntArray(1)
            GLES30.glGenTextures(1, textureIds, 0)
            texture = textureIds[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val framebufferIds = IntArray(1)
            GLES30.glGenFramebuffers(1, framebufferIds, 0)
            framebuffer = framebufferIds[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Incomplete framebuffer: 0x${status.toString(16)}" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }

        fun bind() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        }

        fun release() {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }
}
