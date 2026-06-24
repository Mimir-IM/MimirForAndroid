package com.revertron.mimir

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.ChatListItem
import com.revertron.mimir.ui.ContactsAdapter
import org.bouncycastle.util.encoders.Hex

/**
 * A small copy of the MainActivity to accept media and send it to ChatActivity with selected contact
 */
class SendContentActivity : BaseActivity(), View.OnClickListener {

    companion object {
        const val TAG = "SendContentActivity"
    }

    var sharedUri: Uri? = null
    var sharedText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_content)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.select_contact_to_share)

        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))

        processIntent()
    }

    private fun processIntent() {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    sharedUri = uri
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (text != null) {
                        sharedText = text
                    } else {
                        finish()
                        return
                    }
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {}
            else -> finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent()
    }

    // For now it will not be used
    /*private fun handleMultipleImages(received: Intent) {
        val list = received.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
        list?.forEach { parcelable ->
            (parcelable as? Uri)?.let { uri ->
                val bitmap = loadBitmap(uri)
                showImage(bitmap)
            }
        }
    }*/

    override fun onResume() {
        super.onResume()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        if (recycler.adapter == null) {
            val chatItems = getChatList()
            val adapter = ContactsAdapter(chatItems, this, null)
            recycler.adapter = adapter
            recycler.layoutManager = LinearLayoutManager(this)
        } else {
            refreshContacts()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    @Suppress("NAME_SHADOWING")
    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent, animFromLeft.toBundle())
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onClick(view: View) {
        when (val item = view.tag as ChatListItem) {
            is ChatListItem.SavedMessagesItem -> {
                Log.i(TAG, "Clicked on Saved Messages")
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("contactId", ChatListItem.SAVED_MESSAGES_CONTACT_ID)
                intent.putExtra("name", item.name)
                intent.putExtra("savedMessages", true)
                addSharedContent(intent)
                startActivity(intent, animFromRight.toBundle())
                finish()
            }
            is ChatListItem.ContactItem -> {
                val addr = Hex.toHexString(item.pubkey)
                Log.i(TAG, "Clicked on contact $addr")
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("pubkey", item.pubkey)
                intent.putExtra("name", item.name)
                addSharedContent(intent)
                startActivity(intent, animFromRight.toBundle())
                finish()
            }
            is ChatListItem.GroupChatItem -> {
                Log.i(TAG, "Clicked on group chat ${item.chatId}")
                val intent = Intent(this, GroupChatActivity::class.java)
                intent.putExtra(GroupChatActivity.EXTRA_CHAT_ID, item.chatId)
                intent.putExtra(GroupChatActivity.EXTRA_CHAT_NAME, item.name)
                intent.putExtra(GroupChatActivity.EXTRA_CHAT_DESCRIPTION, item.description)
                intent.putExtra(GroupChatActivity.EXTRA_IS_OWNER, item.isOwner)
                intent.putExtra(GroupChatActivity.EXTRA_MEDIATOR_ADDRESS, item.mediatorAddress)
                addSharedContent(intent)
                startActivity(intent, animFromRight.toBundle())
                finish()
            }
        }
    }

    private fun addSharedContent(intent: Intent) {
        if (sharedUri != null) {
            intent.putExtra(Intent.EXTRA_STREAM, sharedUri)
            // FLAG_GRANT_READ_URI_PERMISSION only applies to URIs in the intent's
            // data or ClipData — not to URIs stored in extras. Attach the URI as
            // ClipData so the read grant actually propagates to the target activity.
            intent.clipData = ClipData.newRawUri("", sharedUri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else if (sharedText != null) {
            intent.putExtra(Intent.EXTRA_TEXT, sharedText)
        }
    }

    private fun getChatList(): List<ChatListItem> {
        val storage = getMimir()
        val contacts = storage.getContacts()
        val groupChats = storage.getGroupChats()

        val chatItems = mutableListOf<ChatListItem>()

        // Convert contacts to ChatListItems
        chatItems.addAll(contacts.map { contact ->
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
        })

        // Convert group chats to ChatListItems
        val myPubKey = storage.getAccount(1)?.pubkey ?: ByteArray(0)
        chatItems.addAll(groupChats.map { groupChat ->
            val avatarDrawable = loadRoundedAvatar(this, groupChat.avatar.ifEmpty { null }, 48, 6)
            val isOwner = myPubKey.contentEquals(groupChat.ownerPubkey)
            val lastMessage = storage.getLastGroupMessage(groupChat.chatId)
            val lastMsgText = lastMessage?.data?.let { String(it) }

            ChatListItem.GroupChatItem(
                id = groupChat.chatId,
                chatId = groupChat.chatId,
                name = groupChat.name,
                description = groupChat.description,
                mediatorAddress = groupChat.mediatorPubkey,
                memberCount = storage.getMemberCount(groupChat.chatId),
                isOwner = isOwner,
                avatar = avatarDrawable,
                lastMessageText = lastMsgText,
                lastMessageTime = groupChat.lastMessageTime,
                unreadCount = groupChat.unreadCount
            )
        })

        // Sort by last message time (most recent first)
        val sortedItems = chatItems.sortedByDescending { it.lastMessageTime }.toMutableList()

        // Add Saved Messages at the top (after sorting)
        val savedMessagesIcon = ContextCompat.getDrawable(this, R.drawable.ic_saved_messages)
        val lastSavedMessage = storage.getLastMessage(ChatListItem.SAVED_MESSAGES_CONTACT_ID)

        // Show tip if there are no saved messages
        val messageText = lastSavedMessage?.data?.let { String(it) } ?: getString(R.string.saved_messages_tip)

        sortedItems.add(0, ChatListItem.SavedMessagesItem(
            name = getString(R.string.saved_messages),
            avatar = savedMessagesIcon,
            lastMessageText = messageText,
            lastMessageTime = lastSavedMessage?.sendTime ?: 0
        ))

        return sortedItems
    }

    private fun refreshContacts() {
        val chatItems = getChatList()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        val adapter = recycler.adapter as ContactsAdapter
        adapter.setContacts(chatItems)
    }
}