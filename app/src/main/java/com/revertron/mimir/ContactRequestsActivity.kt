package com.revertron.mimir

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.ContactRequestsAdapter
import uniffi.mimir.StoredContactRequest

class ContactRequestsActivity : BaseActivity(), ContactRequestsAdapter.Listener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_requests)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.contact_requests)

        val recycler = findViewById<RecyclerView>(R.id.requests_list)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
    }

    override fun onResume() {
        super.onResume()
        refreshRequests()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onAccept(request: StoredContactRequest) {
        val storage = getMimir()
        storage.acceptContactRequest(request.id)

        // Send acceptance response to the peer via ConnectionService
        startService(Intent(this, ConnectionService::class.java).apply {
            putExtra("command", "send_contact_response")
            putExtra("pubkey", request.pubkey)
            putExtra("accepted", true)
        })

        Toast.makeText(this, R.string.contact_request_accepted, Toast.LENGTH_SHORT).show()
        refreshRequests()
    }

    override fun onDecline(request: StoredContactRequest) {
        val storage = getMimir()
        storage.deleteContactRequest(request.id)

        startService(Intent(this, ConnectionService::class.java).apply {
            putExtra("command", "send_contact_response")
            putExtra("pubkey", request.pubkey)
            putExtra("accepted", false)
        })

        Toast.makeText(this, R.string.contact_request_declined, Toast.LENGTH_SHORT).show()
        refreshRequests()
    }

    private fun refreshRequests() {
        val recycler = findViewById<RecyclerView>(R.id.requests_list)
        val requests = getMimir().getContactRequests()

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
