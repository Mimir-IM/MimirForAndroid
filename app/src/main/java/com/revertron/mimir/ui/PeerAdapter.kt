package com.revertron.mimir.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import com.revertron.mimir.storage.PeerEntry

class PeerAdapter(
    private val data: MutableList<PeerEntry>,
    private val onToggle: (Int, Boolean) -> Unit,
    private val onItemClick: (Int, View) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerVH>() {

    inner class PeerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkBox: CheckBox = itemView.findViewById(R.id.peerCheckBox)
        val text: TextView = itemView.findViewById(R.id.peerText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerVH {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_peer, parent, false)
        return PeerVH(view)
    }

    override fun onBindViewHolder(holder: PeerVH, position: Int) {
        val peer = data[position]
        holder.text.text = peer.url
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = peer.enabled
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            onToggle(holder.adapterPosition, isChecked)
        }
        holder.itemView.setOnClickListener {
            onItemClick(holder.adapterPosition, it)
        }
    }

    override fun getItemCount(): Int = data.size
}
