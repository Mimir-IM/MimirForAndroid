package com.revertron.mimir

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.PeerEntry
import com.revertron.mimir.storage.PeerProvider
import com.revertron.mimir.ui.PeerAdapter

class PeersActivity : BaseActivity() {

    private val peers = mutableListOf<PeerEntry>()
    private lateinit var adapter: PeerAdapter
    private lateinit var peerProvider: PeerProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_peers)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        peerProvider = PeerProvider(this)

        val recycler: RecyclerView = findViewById(R.id.recycler)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))

        peers.addAll(peerProvider.getAllPeers())

        adapter = PeerAdapter(peers,
            onToggle = { position, enabled ->
                peers[position] = peers[position].copy(enabled = enabled)
                peerProvider.savePeers(peers)
            },
            onItemClick = { position, view ->
                showPeerPopupMenu(position, view)
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_peers_toolbar, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_peer -> {
                showAddPeerDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }

    override fun onDestroy() {
        val intent = Intent(this, ConnectionService::class.java)
            .putExtra("command", "refresh_peer")
        startService(intent)
        super.onDestroy()
    }

    private fun showPeerPopupMenu(position: Int, anchor: View) {
        val peer = peers[position]
        val popup = PopupMenu(this, anchor)
        popup.inflate(R.menu.menu_context_peer)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.edit_peer -> {
                    showEditPeerDialog(position, peer)
                    true
                }
                R.id.copy_peer -> {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Peer URL", peer.url)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.delete_peer -> {
                    peers.removeAt(position)
                    adapter.notifyItemRemoved(position)
                    peerProvider.savePeers(peers)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAddPeerDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.add_peer_dialog, null)
        val peerUrl = view.findViewById<AppCompatEditText>(R.id.peer_url)
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(getString(R.string.add_custom_peer))
            .setView(view)
            .setIcon(R.drawable.ic_plus_network_outline)
            .setPositiveButton(getString(R.string.add_button)) { _, _ ->
                val url = peerUrl.text.toString().trim()
                if (url.isNotEmpty() && !url.contains("\n")) {
                    peers.add(PeerEntry(url, enabled = true))
                    adapter.notifyItemInserted(peers.size - 1)
                    peerProvider.savePeers(peers)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditPeerDialog(position: Int, peer: PeerEntry) {
        val view = LayoutInflater.from(this).inflate(R.layout.add_peer_dialog, null)
        val peerUrl = view.findViewById<AppCompatEditText>(R.id.peer_url)
        peerUrl.setText(peer.url)
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(getString(R.string.edit_peer))
            .setView(view)
            .setIcon(R.drawable.ic_plus_network_outline)
            .setPositiveButton(R.string.save) { _, _ ->
                val newUrl = peerUrl.text.toString().trim()
                if (newUrl.isNotEmpty() && !newUrl.contains("\n") && newUrl != peer.url) {
                    peers[position] = peer.copy(url = newUrl)
                    adapter.notifyItemChanged(position)
                    peerProvider.savePeers(peers)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
