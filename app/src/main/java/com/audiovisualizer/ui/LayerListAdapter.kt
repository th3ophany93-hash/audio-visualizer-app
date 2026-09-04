package com.audiovisualizer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.audiovisualizer.app.R
import com.audiovisualizer.render.Layer

class LayerListAdapter(
    private val layers: List<Layer>,
    private val onToggle: (Layer, Boolean) -> Unit
) : RecyclerView.Adapter<LayerListAdapter.ViewHolder>() {

    private val expandedLayerIds = mutableSetOf<String>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.layerName)
        val enabledCheckbox: CheckBox = view.findViewById(R.id.layerEnabled)
        val expandToggle: TextView = view.findViewById(R.id.layerExpandToggle)
        val paramsPanel: LinearLayout = view.findViewById(R.id.layerParamsPanel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_layer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val layer = layers[position]
        holder.nameText.text = layer.name
        holder.enabledCheckbox.setOnCheckedChangeListener(null)
        holder.enabledCheckbox.isChecked = layer.enabled
        holder.enabledCheckbox.setOnCheckedChangeListener { _, isChecked ->
            onToggle(layer, isChecked)
        }

        val isExpanded = expandedLayerIds.contains(layer.id)
        applyExpandedState(holder, layer, isExpanded)

        holder.expandToggle.setOnClickListener {
            val nowExpanded = !expandedLayerIds.contains(layer.id)
            if (nowExpanded) expandedLayerIds.add(layer.id) else expandedLayerIds.remove(layer.id)
            applyExpandedState(holder, layer, nowExpanded)
        }
    }

    private fun applyExpandedState(holder: ViewHolder, layer: Layer, expanded: Boolean) {
        holder.expandToggle.text = holder.itemView.context.getString(
            if (expanded) R.string.layer_expanded_indicator else R.string.layer_collapsed_indicator
        )
        if (expanded) {
            LayerParamsPanel.render(holder.itemView.context, holder.paramsPanel, layer) {
                // Layer fields are mutated in place and read fresh every
                // frame by LayerCompositor, so nothing needs re-pushing to
                // the renderer here - this is purely a hook for UI-side
                // bookkeeping if a future control needs it.
            }
            holder.paramsPanel.visibility = View.VISIBLE
        } else {
            holder.paramsPanel.visibility = View.GONE
            holder.paramsPanel.removeAllViews()
        }
    }

    override fun getItemCount(): Int = layers.size
}
