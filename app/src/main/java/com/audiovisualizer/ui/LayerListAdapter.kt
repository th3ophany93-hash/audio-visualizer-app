package com.audiovisualizer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.audiovisualizer.app.R
import com.audiovisualizer.render.Layer

class LayerListAdapter(
    private val layers: List<Layer>,
    private val onToggle: (Layer, Boolean) -> Unit
) : RecyclerView.Adapter<LayerListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.layerName)
        val enabledCheckbox: CheckBox = view.findViewById(R.id.layerEnabled)
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
    }

    override fun getItemCount(): Int = layers.size
}
