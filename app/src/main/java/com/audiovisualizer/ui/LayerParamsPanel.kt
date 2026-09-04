package com.audiovisualizer.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.audiovisualizer.render.AudioBand
import com.audiovisualizer.render.BlendMode
import com.audiovisualizer.render.Layer
import com.audiovisualizer.render.LayerSource
import com.audiovisualizer.render.effects.ColorSpec
import com.audiovisualizer.render.effects.ColorStop
import com.audiovisualizer.render.effects.Effect
import com.audiovisualizer.render.effects.EffectParams
import com.audiovisualizer.render.effects.MovementParams
import com.audiovisualizer.render.effects.ReactionMode
import com.audiovisualizer.render.effects.ReactionTuning
import com.audiovisualizer.render.effects.SpawnZone
import kotlin.math.roundToInt

/**
 * Builds the expandable per-layer parameter panel: sliders/toggles/spinners
 * for the universal [EffectParams] (base, movement, audio reaction) plus
 * whatever's specific to the layer's [LayerSource] (particles/fog/glow/
 * chromatic aberration). Every control mutates the [Layer] object's `var`
 * fields directly - [Layer] and [LayerCompositor][com.audiovisualizer.render.gl.LayerCompositor]
 * share the same instances, so a change is picked up on the very next
 * drawn frame with no extra plumbing.
 */
object LayerParamsPanel {

    /** [ReactionTuning]'s own default attack/release - SMOOTH_CLIMAX's slow envelope. */
    private val SLOW_TUNING = ReactionTuning()

    /** A fast attack/release so BEAT_PULSE actually reads as pulsing to the beat. */
    private val PUNCHY_TUNING = ReactionTuning(attackSeconds = 0.05f, releaseSeconds = 0.25f)

    fun render(context: Context, container: LinearLayout, layer: Layer, onChanged: () -> Unit) {
        container.removeAllViews()
        val rerender = { render(context, container, layer, onChanged) }
        when (val source = layer.source) {
            is LayerSource.Particles -> renderParticles(context, container, layer, source, onChanged, rerender)
            is LayerSource.Fog -> renderFog(context, container, layer, source, onChanged, rerender)
            is LayerSource.Glow -> renderGlow(context, container, layer, source, onChanged, rerender)
            is LayerSource.ChromaticAberration -> renderChromaticAberration(context, container, layer, source, onChanged, rerender)
            is LayerSource.Image, is LayerSource.Video, is LayerSource.Shader -> {
                container.addView(label(context, "У этого типа слоя пока нет настраиваемых параметров."))
            }
        }
    }

    private fun renderParticles(
        context: Context,
        container: LinearLayout,
        layer: Layer,
        source: LayerSource.Particles,
        onChanged: () -> Unit,
        rerender: () -> Unit
    ) {
        baseSection(context, container, layer, onChanged)
        movementSection(context, container, layer, onChanged, enabled = false) // particles use their own speed/gravity instead
        reactionSection(context, container, layer, onChanged, rerender)

        sectionHeader(context, container, "Частицы")
        var params = source.params
        fun update(transform: (Effect.Particles) -> Effect.Particles) {
            params = transform(params)
            layer.source = LayerSource.Particles(params)
            onChanged()
        }
        intSliderRow(context, container, "Количество", 1, 2000, params.count) { v -> update { it.copy(count = v) } }
        sliderRow(context, container, "Размер", 1f, 60f, params.size, 1) { v -> update { it.copy(size = v) } }
        sliderRow(context, container, "Скорость частиц", 0f, 5f, params.speed, 2) { v -> update { it.copy(speed = v) } }
        sliderRow(context, container, "Частота появления", 0f, 5f, params.spawnRate, 2) { v -> update { it.copy(spawnRate = v) } }
        sliderRow(context, container, "Время жизни (с)", 0.1f, 10f, params.lifetime, 2) { v -> update { it.copy(lifetime = v) } }
        sliderRow(context, container, "Гравитация", -2f, 2f, params.gravity, 2) { v -> update { it.copy(gravity = v) } }
    }

    /** [sliderRow] works in Float; particle count is an Int - this just rounds at the edges. */
    private fun intSliderRow(context: Context, container: LinearLayout, label: String, min: Int, max: Int, initial: Int, onChange: (Int) -> Unit) {
        sliderRow(context, container, label, min.toFloat(), max.toFloat(), initial.toFloat(), 0) { onChange(it.roundToInt()) }
    }

    private fun renderFog(
        context: Context,
        container: LinearLayout,
        layer: Layer,
        source: LayerSource.Fog,
        onChanged: () -> Unit,
        rerender: () -> Unit
    ) {
        baseSection(context, container, layer, onChanged)
        movementSection(context, container, layer, onChanged, enabled = true)
        reactionSection(context, container, layer, onChanged, rerender)

        sectionHeader(context, container, "Туман")
        var params = source.params
        fun update(transform: (Effect.Fog) -> Effect.Fog) {
            params = transform(params)
            layer.source = LayerSource.Fog(params)
            onChanged()
        }
        sliderRow(context, container, "Плотность", 0f, 2f, params.density, 2) { v -> update { it.copy(density = v) } }
        sliderRow(context, container, "Масштаб шума", 0.1f, 5f, params.noiseScale, 2) { v -> update { it.copy(noiseScale = v) } }
        sliderRow(context, container, "Дрейф X (ветер)", -0.5f, 0.5f, params.driftSpeedX, 3) { v -> update { it.copy(driftSpeedX = v) } }
        sliderRow(context, container, "Дрейф Y (ветер)", -0.5f, 0.5f, params.driftSpeedY, 3) { v -> update { it.copy(driftSpeedY = v) } }
    }

    private fun renderGlow(
        context: Context,
        container: LinearLayout,
        layer: Layer,
        source: LayerSource.Glow,
        onChanged: () -> Unit,
        rerender: () -> Unit
    ) {
        baseSection(context, container, layer, onChanged)
        movementSection(context, container, layer, onChanged, enabled = true)
        reactionSection(context, container, layer, onChanged, rerender)

        sectionHeader(context, container, "Свечение")
        var params = source.params
        fun update(transform: (Effect.Glow) -> Effect.Glow) {
            params = transform(params)
            layer.source = LayerSource.Glow(params)
            onChanged()
        }
        sliderRow(context, container, "Яркость", 0f, 3f, params.intensity, 2) { v -> update { it.copy(intensity = v) } }
        sliderRow(context, container, "Радиус", 1f, 60f, params.radius, 1) { v -> update { it.copy(radius = v) } }
    }

    private fun renderChromaticAberration(
        context: Context,
        container: LinearLayout,
        layer: Layer,
        source: LayerSource.ChromaticAberration,
        onChanged: () -> Unit,
        rerender: () -> Unit
    ) {
        baseSection(context, container, layer, onChanged, showColor = false)
        reactionSection(context, container, layer, onChanged, rerender)

        sectionHeader(context, container, "Хроматическая аберрация")
        var params = source.params
        sliderRow(context, container, "Сила эффекта", 0f, 0.2f, params.strength, 3) { v ->
            params = params.copy(strength = v)
            layer.source = LayerSource.ChromaticAberration(params)
            onChanged()
        }
    }

    // ---- Universal sections -------------------------------------------------

    private fun baseSection(context: Context, container: LinearLayout, layer: Layer, onChanged: () -> Unit, showColor: Boolean = true) {
        sectionHeader(context, container, "Основное")
        var params = layer.effectParams
        fun update(transform: (EffectParams) -> EffectParams) {
            params = transform(params)
            layer.effectParams = params
            onChanged()
        }

        switchRow(context, container, "Включено", params.enabled) { v -> update { it.copy(enabled = v) } }
        sliderRow(context, container, "Непрозрачность", 0f, 1f, params.opacity, 2) { v -> update { it.copy(opacity = v) } }
        spinnerRow(context, container, "Режим смешивания", BlendMode.entries.map { it.name }, params.blendMode.ordinal) { i ->
            update { it.copy(blendMode = BlendMode.entries[i]) }
        }
        sliderRow(context, container, "Масштаб X", 0.1f, 3f, params.scaleX, 2) { v -> update { it.copy(scaleX = v) } }
        sliderRow(context, container, "Масштаб Y", 0.1f, 3f, params.scaleY, 2) { v -> update { it.copy(scaleY = v) } }

        zoneRow(context, container, params.zone) { z -> update { it.copy(zone = z) } }
        if (showColor) {
            colorSpecRow(context, container, params.color) { c -> update { it.copy(color = c) } }
        }
    }

    private fun movementSection(context: Context, container: LinearLayout, layer: Layer, onChanged: () -> Unit, enabled: Boolean) {
        if (!enabled) return
        sectionHeader(context, container, "Движение")
        var params = layer.effectParams
        fun update(transform: (MovementParams) -> MovementParams) {
            params = params.copy(movement = transform(params.movement))
            layer.effectParams = params
            onChanged()
        }

        switchRow(context, container, "Дрейф включён", params.movement.enabled) { v -> update { it.copy(enabled = v) } }
        sliderRow(context, container, "Скорость", 0f, 3f, params.movement.speed, 2) { v -> update { it.copy(speed = v) } }
        sliderRow(context, container, "Направление (рад.)", -3.14f, 3.14f, params.movement.direction, 2) { v -> update { it.copy(direction = v) } }
    }

    private fun reactionSection(context: Context, container: LinearLayout, layer: Layer, onChanged: () -> Unit, rerender: () -> Unit) {
        sectionHeader(context, container, "Реакция на звук")
        var params = layer.effectParams
        fun update(transform: (EffectParams) -> EffectParams) {
            params = transform(params)
            layer.effectParams = params
            onChanged()
        }
        fun updateTuning(transform: (ReactionTuning) -> ReactionTuning) {
            update { it.copy(reactionTuning = transform(it.reactionTuning)) }
        }

        spinnerRow(context, container, "Режим реакции", ReactionMode.entries.map { it.name }, params.reactionMode.ordinal) { i ->
            val newMode = ReactionMode.entries[i]
            // BEAT_PULSE needs a fast attack/release to actually read as
            // "pulsing to the beat" - SMOOTH_CLIMAX's slow envelope (the
            // shared ReactionTuning default) makes every mode look like the
            // same gentle ambient drift within a normal test session. Only
            // swap the preset when the tuning still matches the mode being
            // left, so a deliberately hand-tuned value is never clobbered.
            val tuning = params.reactionTuning
            val adjustedTuning = when {
                newMode == ReactionMode.BEAT_PULSE && tuning.attackSeconds == SLOW_TUNING.attackSeconds && tuning.releaseSeconds == SLOW_TUNING.releaseSeconds ->
                    tuning.copy(attackSeconds = PUNCHY_TUNING.attackSeconds, releaseSeconds = PUNCHY_TUNING.releaseSeconds)
                newMode != ReactionMode.BEAT_PULSE && tuning.attackSeconds == PUNCHY_TUNING.attackSeconds && tuning.releaseSeconds == PUNCHY_TUNING.releaseSeconds ->
                    tuning.copy(attackSeconds = SLOW_TUNING.attackSeconds, releaseSeconds = SLOW_TUNING.releaseSeconds)
                else -> tuning
            }
            update { it.copy(reactionMode = newMode, reactionTuning = adjustedTuning) }
            rerender()
        }
        switchRow(context, container, "Мерцание на удар (beat flicker)", params.beatFlicker) { v -> update { it.copy(beatFlicker = v) } }

        spinnerRow(context, container, "Полоса частот", AudioBand.entries.map { it.name }, params.reactionTuning.band.ordinal) { i ->
            updateTuning { it.copy(band = AudioBand.entries[i]) }
        }
        sliderRow(context, container, "Чувствительность", 0f, 4f, params.reactionTuning.sensitivity, 2) { v -> updateTuning { it.copy(sensitivity = v) } }
        sliderRow(context, container, "Порог", 0f, 1f, params.reactionTuning.threshold, 2) { v -> updateTuning { it.copy(threshold = v) } }
        sliderRow(context, container, "Атака (с)", 0.01f, 10f, params.reactionTuning.attackSeconds, 2) { v -> updateTuning { it.copy(attackSeconds = v) } }
        sliderRow(context, container, "Спад (с)", 0.01f, 20f, params.reactionTuning.releaseSeconds, 2) { v -> updateTuning { it.copy(releaseSeconds = v) } }
        sliderRow(context, container, "Мин. интенсивность", 0f, 2f, params.reactionTuning.minIntensity, 2) { v -> updateTuning { it.copy(minIntensity = v) } }
        sliderRow(context, container, "Макс. интенсивность", 0f, 2f, params.reactionTuning.maxIntensity, 2) { v -> updateTuning { it.copy(maxIntensity = v) } }
    }

    // ---- Zone editing ---------------------------------------------------------

    private fun zoneRow(context: Context, container: LinearLayout, initial: SpawnZone, onChange: (SpawnZone) -> Unit) {
        sectionHeader(context, container, "Зона", small = true)
        val fieldsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun renderFields(zone: SpawnZone) {
            fieldsContainer.removeAllViews()
            when (zone) {
                is SpawnZone.FullScreen -> Unit
                is SpawnZone.Rect -> {
                    sliderRow(context, fieldsContainer, "X", 0f, 1f, zone.x, 2) { v -> onChange(zone.copy(x = v)) }
                    sliderRow(context, fieldsContainer, "Y", 0f, 1f, zone.y, 2) { v -> onChange(zone.copy(y = v)) }
                    sliderRow(context, fieldsContainer, "Ширина", 0f, 1f, zone.width, 2) { v -> onChange(zone.copy(width = v)) }
                    sliderRow(context, fieldsContainer, "Высота", 0f, 1f, zone.height, 2) { v -> onChange(zone.copy(height = v)) }
                }
                is SpawnZone.Circle -> {
                    sliderRow(context, fieldsContainer, "Центр X", 0f, 1f, zone.centerX, 2) { v -> onChange(zone.copy(centerX = v)) }
                    sliderRow(context, fieldsContainer, "Центр Y", 0f, 1f, zone.centerY, 2) { v -> onChange(zone.copy(centerY = v)) }
                    sliderRow(context, fieldsContainer, "Радиус", 0f, 1f, zone.radius, 2) { v -> onChange(zone.copy(radius = v)) }
                }
            }
        }

        val typeNames = listOf("Весь экран", "Прямоугольник", "Круг")
        val initialIndex = when (initial) {
            is SpawnZone.FullScreen -> 0
            is SpawnZone.Rect -> 1
            is SpawnZone.Circle -> 2
        }
        spinnerRow(context, container, "Тип зоны", typeNames, initialIndex) { i ->
            val zone = when (i) {
                1 -> SpawnZone.Rect(x = 0f, y = 0f, width = 1f, height = 1f)
                2 -> SpawnZone.Circle(centerX = 0.5f, centerY = 0.5f, radius = 0.3f)
                else -> SpawnZone.FullScreen
            }
            onChange(zone)
            renderFields(zone)
        }
        renderFields(initial)
        container.addView(fieldsContainer)
    }

    // ---- Color / gradient editing ---------------------------------------------

    private fun colorSpecRow(context: Context, container: LinearLayout, initial: ColorSpec, onChange: (ColorSpec) -> Unit) {
        sectionHeader(context, container, "Цвет (1-3 точки градиента)", small = true)
        val stopsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var current = initial

        fun renderStops() {
            stopsContainer.removeAllViews()
            current.stops.forEachIndexed { index, stop ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(context, 2), 0, dp(context, 2))
                }
                val swatch = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(context, 28), dp(context, 28))
                    setBackgroundColor(stop.color)
                }
                swatch.setOnClickListener {
                    showColorDialog(context, stop.color) { newColor ->
                        val stops = current.stops.toMutableList()
                        stops[index] = stops[index].copy(color = newColor)
                        current = ColorSpec(stops)
                        onChange(current)
                        renderStops()
                    }
                }
                row.addView(swatch)

                val posLabel = TextView(context).apply {
                    text = "  позиция: %.2f".format(stop.position)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(posLabel)

                if (current.stops.size > 1) {
                    val remove = Button(context).apply { text = "✕" }
                    remove.setOnClickListener {
                        val stops = current.stops.toMutableList()
                        stops.removeAt(index)
                        current = ColorSpec(stops)
                        onChange(current)
                        renderStops()
                    }
                    row.addView(remove)
                }
                stopsContainer.addView(row)

                if (current.stops.size > 1) {
                    sliderRow(context, stopsContainer, "  позиция точки ${index + 1}", 0f, 1f, stop.position, 2) { v ->
                        val stops = current.stops.toMutableList()
                        stops[index] = stops[index].copy(position = v)
                        current = ColorSpec(stops)
                        onChange(current)
                    }
                }
            }

            if (current.stops.size < 3) {
                val add = Button(context).apply { text = "+ добавить точку" }
                add.setOnClickListener {
                    val stops = current.stops.toMutableList()
                    val last = stops.last()
                    stops.add(ColorStop(last.color, (last.position + 1f) / 2f))
                    current = ColorSpec(stops)
                    onChange(current)
                    renderStops()
                }
                stopsContainer.addView(add)
            }
        }

        renderStops()
        container.addView(stopsContainer)
    }

    private fun showColorDialog(context: Context, initial: Int, onPicked: (Int) -> Unit) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
        }
        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 32))
            setBackgroundColor(initial)
        }
        layout.addView(preview)

        var a = (initial ushr 24) and 0xFF
        var r = (initial ushr 16) and 0xFF
        var g = (initial ushr 8) and 0xFF
        var b = initial and 0xFF

        fun currentColor() = Color.argb(a, r, g, b)

        fun channelRow(name: String, initialValue: Int, onSet: (Int) -> Unit) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val text = TextView(context).apply {
                text = "$name: $initialValue"
                layoutParams = LinearLayout.LayoutParams(dp(context, 50), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val seek = SeekBar(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                max = 255
                progress = initialValue
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    text.text = "$name: $progress"
                    onSet(progress)
                    preview.setBackgroundColor(currentColor())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            row.addView(text)
            row.addView(seek)
            layout.addView(row)
        }

        channelRow("A", a) { a = it }
        channelRow("R", r) { r = it }
        channelRow("G", g) { g = it }
        channelRow("B", b) { b = it }

        AlertDialog.Builder(context)
            .setTitle("Выбор цвета")
            .setView(layout)
            .setPositiveButton("ОК") { _, _ -> onPicked(currentColor()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---- Generic row widgets ----------------------------------------------

    private fun sectionHeader(context: Context, container: LinearLayout, title: String, small: Boolean = false) {
        val view = TextView(context).apply {
            text = title
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = if (small) 13f else 15f
            setPadding(0, dp(context, if (small) 6 else 12), 0, dp(context, 4))
        }
        container.addView(view)
    }

    private fun label(context: Context, text: String): TextView = TextView(context).apply { this.text = text }

    private fun switchRow(context: Context, container: LinearLayout, label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(context).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val switch = Switch(context).apply { isChecked = initial }
        switch.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        row.addView(switch)
        container.addView(row)
    }

    private fun sliderRow(
        context: Context,
        container: LinearLayout,
        label: String,
        rangeMin: Float,
        rangeMax: Float,
        initial: Float,
        decimals: Int,
        onChange: (Float) -> Unit
    ) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val format = "%.${decimals}f"
        val text = TextView(context).apply {
            text = "$label: ${format.format(initial)}"
        }
        val seek = SeekBar(context)
        seek.max = 1000
        seek.progress = (((initial - rangeMin) / (rangeMax - rangeMin)).coerceIn(0f, 1f) * 1000).roundToInt()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = rangeMin + (progress / 1000f) * (rangeMax - rangeMin)
                text.text = "$label: ${format.format(value)}"
                if (fromUser) onChange(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        row.addView(text)
        row.addView(seek)
        container.addView(row)
    }

    private fun spinnerRow(context: Context, container: LinearLayout, label: String, options: List<String>, initialIndex: Int, onChange: (Int) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(context).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val spinner = Spinner(context)
        spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.setSelection(initialIndex, false)
        var firstCall = true
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (firstCall) {
                    firstCall = false
                    return
                }
                onChange(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        row.addView(spinner)
        container.addView(row)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
