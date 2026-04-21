package com.revertron.mimir

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.InvitesAdapter
import uniffi.mimir.StoredInvite

class InviteListActivity : BaseActivity(), View.OnClickListener {

    companion object {
        const val TAG = "InviteListActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invite_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.group_invites)

        val recycler = findViewById<RecyclerView>(R.id.invites_list)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
    }

    override fun onResume() {
        super.onResume()
        refreshInvites()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onClick(v: View?) {
        if (v != null && v.tag is StoredInvite) {
            val invite = v.tag as StoredInvite
            val intent = Intent(this, GroupInviteActivity::class.java).apply {
                putExtra("invite_id", invite.id)
                putExtra("chat_id", invite.chatId)
                putExtra("from_pubkey", invite.fromPubkey)
                putExtra("chat_name", invite.chatName)
                putExtra("chat_description", invite.chatDescription)
                putExtra("chat_avatar_path", invite.chatAvatar)
                putExtra("encrypted_data", invite.encryptedData)
                putExtra("timestamp", invite.timestamp)
                putExtra("mediator_pubkey", invite.mediatorPubkey)
            }
            startActivity(intent)
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.hold_still)
        }
    }

    private fun refreshInvites() {
        val recycler = findViewById<RecyclerView>(R.id.invites_list)
        val invites = getStorage().getPendingInvites()

        if (recycler.adapter == null) {
            val adapter = InvitesAdapter(invites, this)
            recycler.adapter = adapter
            recycler.layoutManager = LinearLayoutManager(this)
        } else {
            (recycler.adapter as InvitesAdapter).setInvites(invites)
        }

        // Show empty state if no invites
        val emptyView = findViewById<View>(R.id.empty_view)
        if (invites.isEmpty()) {
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
