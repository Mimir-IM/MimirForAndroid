package com.revertron.mimir

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.ContactRequest
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.ContactRequestsAdapter
import org.bouncycastle.util.encoders.Hex

class ContactRequestsActivity : BaseActivity(), ContactRequestsAdapter.Listener, StorageListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_requests)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.contact_requests)

        getStorage().listeners.add(this)

        val recycler = findViewById<RecyclerView>(R.id.requests_list)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
    }

    override fun onResume() {
        super.onResume()
        refreshRequests()
    }

    override fun onDestroy() {
        getStorage().listeners.remove(this)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onAccept(request: ContactRequest) {
        val storage = getStorage()
        storage.acceptContactRequest(request.id)

        // Send acceptance response to the peer
        try {
            App.app.peerNode?.sendContactResponse(request.pubkey, true)
        } catch (e: Exception) {
            // Peer may not be connected right now — that's OK, the contact is created
        }

        Toast.makeText(this, R.string.contact_request_accepted, Toast.LENGTH_SHORT).show()
        refreshRequests()
    }

    override fun onDecline(request: ContactRequest) {
        val storage = getStorage()
        storage.deleteContactRequest(request.id)

        try {
            App.app.peerNode?.sendContactResponse(request.pubkey, false)
        } catch (_: Exception) {}

        Toast.makeText(this, R.string.contact_request_declined, Toast.LENGTH_SHORT).show()
        refreshRequests()
    }

    override fun onContactRequestReceived(requestId: Long) {
        runOnUiThread { refreshRequests() }
    }

    private fun refreshRequests() {
        val recycler = findViewById<RecyclerView>(R.id.requests_list)
        val requests = getStorage().getContactRequests()

        if (recycler.adapter == null) {
            recycler.adapter = ContactRequestsAdapter(requests, this)
            recycler.layoutManager = LinearLayoutManager(this)
        } else {
            (recycler.adapter as ContactRequestsAdapter).setRequests(requests)
        }

        val emptyView = findViewById<View>(R.id.empty_view)
        if (requests.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }
}
