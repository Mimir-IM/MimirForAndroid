package com.revertron.mimir.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.revertron.mimir.R
import com.revertron.mimir.loadRoundedAvatar
import com.revertron.mimir.storage.ContactRequest

class ContactRequestsAdapter(
    private var requests: List<ContactRequest>,
    private val listener: Listener
) : RecyclerView.Adapter<ContactRequestsAdapter.ViewHolder>() {

    interface Listener {
        fun onAccept(request: ContactRequest)
        fun onDecline(request: ContactRequest)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.avatar)
        val nickname: TextView = view.findViewById(R.id.nickname)
        val message: TextView = view.findViewById(R.id.message)
        val btnAccept: MaterialButton = view.findViewById(R.id.btn_accept)
        val btnDecline: MaterialButton = view.findViewById(R.id.btn_decline)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact_request, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val req = requests[position]
        holder.nickname.text = req.nickname.ifEmpty { "Unknown" }
        holder.message.text = req.message.ifEmpty { req.info }
        holder.message.visibility = if (holder.message.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        if (req.avatarPath != null) {
            val drawable = loadRoundedAvatar(holder.avatar.context, req.avatarPath, 48, 6)
            if (drawable != null) {
                holder.avatar.setImageDrawable(drawable)
            }
        }

        holder.btnAccept.setOnClickListener { listener.onAccept(req) }
        holder.btnDecline.setOnClickListener { listener.onDecline(req) }
    }

    override fun getItemCount() = requests.size

    fun setRequests(newRequests: List<ContactRequest>) {
        requests = newRequests
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }
}
