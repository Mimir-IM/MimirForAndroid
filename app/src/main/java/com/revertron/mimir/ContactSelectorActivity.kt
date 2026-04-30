package com.revertron.mimir

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.ChatListItem
import com.revertron.mimir.ui.ContactsAdapter
import org.bouncycastle.util.encoders.Hex

/**
 * Reusable activity for selecting a contact from the user's contact list.
 *
 * Usage:
 * ```
 * val intent = Intent(this, ContactSelectorActivity::class.java).apply {
 *     putExtra(EXTRA_TITLE, "Select Contact")
 *     putExtra(EXTRA_FILTER_PUBKEYS, arrayOf(...)) // optional: exclude specific contacts
 * }
 * startActivityForResult(intent, REQUEST_SELECT_CONTACT)
 *
 * // In onActivityResult:
 * if (resultCode == RESULT_OK && data != null) {
 *     val pubkey = data.getByteArrayExtra(RESULT_PUBKEY)
 *     val name = data.getStringExtra(RESULT_NAME)
 * }
 * ```
 */
class ContactSelectorActivity : BaseActivity(), View.OnClickListener {

    companion object {
        const val TAG = "ContactSelectorActivity"

        // Intent extras
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILTER_PUBKEYS = "filter_pubkeys" // ByteArray array to exclude

        // Result extras
        const val RESULT_PUBKEY = "pubkey"
        const val RESULT_NAME = "name"
        const val RESULT_CONTACT_ID = "contact_id"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ContactsAdapter
    private var filterPubkeys: Set<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_content)

        // Get title from intent or use default
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.select_contact_to_share)

        // Setup toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = title

        // Get filter list if provided
        @Suppress("DEPRECATION")
        val filterArray = intent.getSerializableExtra(EXTRA_FILTER_PUBKEYS) as? Array<ByteArray>
        filterPubkeys = filterArray?.map { org.bouncycastle.util.encoders.Hex.toHexString(it) }?.toSet()

        // Setup RecyclerView
        recyclerView = findViewById(R.id.contacts_list)
        recyclerView.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadContacts()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            refreshContacts()
        }
    }

    private fun loadContacts() {
        val chatItems = getFilteredContactItems()
        adapter = ContactsAdapter(chatItems, this, null)
        recyclerView.adapter = adapter
    }

    private fun refreshContacts() {
        val chatItems = getFilteredContactItems()
        adapter.setContacts(chatItems)
    }

    private fun getFilteredContactItems(): List<ChatListItem> {
        val storage = getMimir()
        val allContacts = storage.getContacts()

        // Filter out contacts if filter list provided
        val contacts = if (filterPubkeys != null) {
            allContacts.filter { contact ->
                val pubkeyHex = Hex.toHexString(contact.pubkey)
                !filterPubkeys!!.contains(pubkeyHex)
            }
        } else {
            allContacts
        }

        // Convert to ChatListItem, most-recently-active first. Contacts with
        // no messages have lastMessageTime=0 and sink to the bottom.
        return contacts.map { contact ->
            val avatarDrawable = loadRoundedAvatar(this, contact.avatar.ifEmpty { null }, 48, 6)
            val lastMessage = storage.getLastMessage(contact.id)
            val lastMsgText = lastMessage?.data?.let { String(it) }

            ChatListItem.ContactItem(
                id = contact.id,
                pubkey = contact.pubkey,
                name = contact.name,
                lastMessageText = lastMsgText,
                lastMessageTime = lastMessage?.sendTime ?: 0,
                unreadCount = storage.getUnreadCount(contact.id),
                avatar = avatarDrawable
            )
        }.sortedByDescending { it.lastMessageTime }
    }

    override fun onClick(view: View) {
        if (view.tag is ChatListItem.ContactItem) {
            val contactItem = view.tag as ChatListItem.ContactItem

            // Return selected contact via result
            val resultIntent = Intent().apply {
                putExtra(RESULT_PUBKEY, contactItem.pubkey)
                putExtra(RESULT_NAME, contactItem.name)
                putExtra(RESULT_CONTACT_ID, contactItem.id)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
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

}