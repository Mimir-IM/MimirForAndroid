package com.revertron.mimir.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import com.revertron.mimir.getImagePreview
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileItem(
    val storageName: String,
    val originalName: String,
    val size: Long,
    val lastModified: Long,
    val isImage: Boolean,
    val chatName: String = ""
)

class FilesAdapter(private val listener: Listener) : RecyclerView.Adapter<FilesAdapter.ViewHolder>() {

    interface Listener {
        fun onFileClicked(item: FileItem, position: Int)
        fun onFileLongClicked(item: FileItem, position: Int): Boolean
    }

    private var items: List<FileItem> = emptyList()
    private val selectedPositions = mutableSetOf<Int>()
    private val dateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun setItems(newItems: List<FileItem>) {
        items = newItems
        selectedPositions.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(position: Int) {
        if (position in selectedPositions) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)
    }

    fun clearSelection() {
        val old = selectedPositions.toSet()
        selectedPositions.clear()
        for (pos in old) {
            notifyItemChanged(pos)
        }
    }

    fun getSelectedItems(): List<FileItem> {
        return selectedPositions.sorted().map { items[it] }
    }

    fun isInSelectionMode(): Boolean = selectedPositions.isNotEmpty()

    fun selectedCount(): Int = selectedPositions.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_file_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.fileName.text = item.originalName
        val sizeStr = formatFileSize(item.size)
        val dateStr = dateFormatter.format(Date(item.lastModified))
        holder.fileInfo.text = if (item.chatName.isNotEmpty()) {
            "${item.chatName} · $sizeStr · $dateStr"
        } else {
            "$sizeStr · $dateStr"
        }

        if (item.isImage) {
            val preview = getImagePreview(context, item.storageName, 96, 70)
            if (preview != null) {
                holder.fileIcon.setImageBitmap(preview)
            } else {
                holder.fileIcon.setImageResource(R.drawable.ic_file_image_outline)
            }
        } else {
            holder.fileIcon.setImageResource(R.drawable.ic_file_document_outline)
        }

        holder.itemView.isActivated = position in selectedPositions
        holder.itemView.setOnClickListener { listener.onFileClicked(item, position) }
        holder.itemView.setOnLongClickListener { listener.onFileLongClicked(item, position) }
    }

    override fun getItemCount(): Int = items.size

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val fileName: TextView = view.findViewById(R.id.fileName)
        val fileInfo: TextView = view.findViewById(R.id.fileInfo)
    }
}
