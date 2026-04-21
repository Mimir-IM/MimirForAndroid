package com.revertron.mimir

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.FileItem
import com.revertron.mimir.ui.FilesAdapter
import java.io.File

class FilesActivity : BaseActivity(), FilesAdapter.Listener {

    private enum class SortOrder { NAME, SIZE, DATE }

    private lateinit var adapter: FilesAdapter
    private lateinit var emptyText: TextView
    private var sortOrder = SortOrder.DATE
    private var deleteMenuItem: MenuItem? = null
    private var normalSubtitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        emptyText = findViewById(R.id.emptyText)

        adapter = FilesAdapter(this)
        val recycler = findViewById<RecyclerView>(R.id.filesRecyclerView)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, RecyclerView.VERTICAL))
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_files, menu)
        deleteMenuItem = menu.findItem(R.id.action_delete)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_delete -> {
                showBatchDeleteDialog()
                true
            }
            R.id.sort_by_name -> {
                sortOrder = SortOrder.NAME
                loadFiles()
                true
            }
            R.id.sort_by_size -> {
                sortOrder = SortOrder.SIZE
                loadFiles()
                true
            }
            R.id.sort_by_date -> {
                sortOrder = SortOrder.DATE
                loadFiles()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (adapter.isInSelectionMode()) {
            adapter.clearSelection()
            updateSelectionUI()
        } else {
            super.onBackPressed()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }

    private fun loadFiles() {
        val filesDirPath = File(filesDir, "files")
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp")

        // DB metadata for every sent/received attachment, keyed by on-disk filename.
        val dbMap: Map<String, uniffi.mimir.FileItem> =
            getStorage().getAllFileItems().associateBy { it.storageName }

        // Cache contact/group names so we don't re-query for every row.
        val contactNameCache = mutableMapOf<Long, String>()
        val groupNameCache = mutableMapOf<Long, String>()

        fun resolveChatName(contactId: Long, chatId: Long): String {
            return when {
                contactId > 0 -> contactNameCache.getOrPut(contactId) {
                    getStorage().getContact(contactId)?.name ?: ""
                }
                chatId > 0 -> groupNameCache.getOrPut(chatId) {
                    getStorage().getGroupChat(chatId)?.name ?: ""
                }
                else -> ""
            }
        }

        val items = mutableListOf<FileItem>()

        if (filesDirPath.exists() && filesDirPath.isDirectory) {
            filesDirPath.listFiles()?.forEach { file ->
                val meta = dbMap[file.name]
                if (meta != null) {
                    items.add(FileItem(
                        storageName = file.name,
                        originalName = meta.originalName,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        isImage = meta.isImage || meta.mimeType.startsWith("image/"),
                        chatName = resolveChatName(meta.contactId, meta.chatId)
                    ))
                } else {
                    // Orphan: on disk but no DB row (e.g. leftover from uninstalled chat).
                    val ext = file.extension.lowercase()
                    items.add(FileItem(
                        storageName = file.name,
                        originalName = file.name,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        isImage = ext in imageExtensions,
                        chatName = ""
                    ))
                }
            }
        }

        val sorted = when (sortOrder) {
            SortOrder.NAME -> items.sortedBy { it.originalName.lowercase() }
            SortOrder.SIZE -> items.sortedByDescending { it.size }
            SortOrder.DATE -> items.sortedByDescending { it.lastModified }
        }

        adapter.setItems(sorted)
        updateSelectionUI()

        if (sorted.isEmpty()) {
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }

        val totalSize = items.sumOf { it.size }
        normalSubtitle = "${items.size} files - ${formatFileSize(totalSize)}"
        supportActionBar?.subtitle = normalSubtitle
    }

    override fun onFileClicked(item: FileItem, position: Int) {
        if (adapter.isInSelectionMode()) {
            adapter.toggleSelection(position)
            updateSelectionUI()
        } else {
            openFile(item)
        }
    }

    override fun onFileLongClicked(item: FileItem, position: Int): Boolean {
        adapter.toggleSelection(position)
        updateSelectionUI()
        return true
    }

    private fun updateSelectionUI() {
        if (adapter.isInSelectionMode()) {
            deleteMenuItem?.isVisible = true
            supportActionBar?.subtitle = "${adapter.selectedCount()} selected"
        } else {
            deleteMenuItem?.isVisible = false
            if (normalSubtitle != null) {
                supportActionBar?.subtitle = normalSubtitle
            }
        }
    }

    private fun showBatchDeleteDialog() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) return

        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val message = if (selected.size == 1) {
            getString(R.string.delete_file_confirm, selected.first().originalName)
        } else {
            getString(R.string.delete_files_confirm, selected.size)
        }
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.delete_file_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                for (item in selected) {
                    deleteFileAndPreview(this, item.storageName)
                }
                adapter.clearSelection()
                loadFiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openFile(item: FileItem) {
        val file = File(File(filesDir, "files"), item.storageName)
        if (!file.exists()) {
            Toast.makeText(this, R.string.file_not_found_in_storage, Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = getMimeTypeFromFilename(item.originalName)
        openFile(file, mimeType)
    }

    private fun openFile(file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.file_provider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, mimeType)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooserIntent = Intent.createChooser(intent, getString(R.string.open_with))
            try {
                startActivity(chooserIntent)
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.no_app_found_to_open, file.name), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
